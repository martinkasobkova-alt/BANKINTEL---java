package cz.bankintel.explore;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.CatalogSourceRegistry;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
@RequiredArgsConstructor
public class ExploreStreamService {

    private static final Logger log = LoggerFactory.getLogger(ExploreStreamService.class);

    // Sized well above the worst single (non-duplicated) discovery pass observed live (~185s,
    // dominated by a single slow source lane) - NOT a blind bump, see docs/archive/MANAGER_EXPLORER_AUDIT_V2.md
    // section 1.1. Previously this had to cover TWO full discovery passes back to back (the
    // now-removed discoverWithLanes + analyzeSector double-run), which is why 120s used to be
    // too short even though a single pass usually fits comfortably inside it.
    private static final long SSE_TIMEOUT_MS = 300_000L;
    private static final long HEARTBEAT_INTERVAL_MS = 15_000L;

    private final ExploreSectorService sectorService;
    private final ExploreDiscoveryService exploreDiscoveryService;
    private final ObjectMapper objectMapper;

    public SseEmitter streamSector(
            String sector,
            String question,
            String country,
            String geoMode,
            String continent,
            String relatedSegments,
            String relatedSegmentRanking,
            String analysisMode) {
        return streamSector(
                sector,
                question,
                country,
                geoMode,
                continent,
                relatedSegments,
                relatedSegmentRanking,
                analysisMode,
                null);
    }

    public SseEmitter streamSector(
            String sector,
            String question,
            String country,
            String geoMode,
            String continent,
            String relatedSegments,
            String relatedSegmentRanking,
            String analysisMode,
            String requestId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        DiscoveryRunContext run = DiscoveryRunContext.create(requestId);
        emitter.onCompletion(() -> cancelRunIfActive(run, "client_completed"));
        emitter.onTimeout(() -> cancelRunIfActive(run, "sse_timeout"));
        emitter.onError(error -> cancelRunIfActive(run, "client_error"));

        Thread worker = Thread.ofVirtual().name("manager-explorer-" + run.discoveryRunId()).start(() -> runStream(
                emitter,
                run,
                sector,
                question,
                country,
                geoMode,
                continent,
                relatedSegments,
                relatedSegmentRanking,
                analysisMode));
        run.workerThread().set(worker);
        if (run.cancelled().get()) {
            worker.interrupt();
        }
        return emitter;
    }

    private void runStream(
            SseEmitter emitter,
            DiscoveryRunContext run,
            String sector,
            String question,
            String country,
            String geoMode,
            String continent,
            String relatedSegments,
            String relatedSegmentRanking,
            String analysisMode) {
        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
        try {
            ensureActive(run);
            log.info(
                    "manager_explorer_discovery status=started request_id={} discovery_run_id={}",
                    run.requestId(),
                    run.discoveryRunId());
            heartbeat.scheduleAtFixedRate(
                    () -> sendHeartbeat(emitter, run),
                    HEARTBEAT_INTERVAL_MS,
                    HEARTBEAT_INTERVAL_MS,
                    TimeUnit.MILLISECONDS);

            Map<String, Object> preset = sectorService.buildPresetPreview(sector, question, country, geoMode, continent);
            sendEvent(emitter, run, "preset_ready", preset);

            Map<String, Object> seed = new LinkedHashMap<>(preset);
            seed.put("partial", true);
            seed.put("deep_search_status", "running");
            sendEvent(emitter, run, "indicators_update", seed);

            ExploreDtos.ExploreSectorRequest request = new ExploreDtos.ExploreSectorRequest(
                    sector == null ? "" : sector,
                    question,
                    country,
                    List.of(),
                    geoMode,
                    continent,
                    relatedSegments,
                    relatedSegmentRanking,
                    analysisMode == null ? "sector" : analysisMode,
                    false,
                    false,
                    false,
                    false,
                    List.of(),
                    "strict_private",
                    "auto",
                    null,
                    question != null && !question.isBlank() && (sector == null || sector.isBlank()));

            ExploreSectorService.PreparedAnalysis prep = sectorService.prepareAnalysis(request);
            if (prep.shortCircuit() != null) {
                Map<String, Object> shortCircuit = withRunObservability(prep.shortCircuit(), run, "completed");
                sendEvent(emitter, run, "search_finished", shortCircuit);
                logCompletion(run, shortCircuit, "completed");
                run.terminal().set(true);
                emitter.complete();
                return;
            }

            // All requested sources are dispatched to the lane executor together (see
            // CatalogDeepSearchService.deepSearchWithLanes), so "started" for every one of them is
            // genuinely true right now - not synthesized per-lane-completion like before.
            //
            // Normalized the same way the query planner normalizes them before dispatch (e.g.
            // "ecb" -> "ecb2") - EXPLORE_DISCOVERY_DEFAULT_SOURCES uses the registry alias, but
            // searchLane's onLane callback reports whatever plan.sources() actually dispatched,
            // which is already normalized. Without this, "ecb" never matches its own
            // "source_finished" (reported as "ecb2") and gets reported as a false source_timeout
            // even when it returned real results - confirmed live: verified with the real
            // /explore/sector/stream endpoint before applying this fix.
            List<String> requestedSources = CatalogSourceRegistry.EXPLORE_DISCOVERY_DEFAULT_SOURCES.stream()
                    .map(CatalogSourceRegistry::normalizeSearchSource)
                    .distinct()
                    .toList();
            for (String source : requestedSources) {
                run.sourceTerminalStatuses().put(source, "running");
                sendProgress(emitter, run, "source_started", Map.of("source", source, "query", prep.question()));
            }

            // Single discovery pass: progress events stream out via the lane callback below, and
            // the same call's return value becomes the final result - no second discovery run.
            AtomicReference<Map<String, Object>> latest = new AtomicReference<>(seed);
            Set<String> finishedSources = ConcurrentHashMap.newKeySet();
            run.fullDiscoveryRunCount().incrementAndGet();
            List<String> resolvedCountryCodes = ExploreGeoResolver.countryCodesFrom(prep.ctx().get("geo"));
            ExploreDiscoveryService.LaneConsumer laneConsumer = (source, lane) -> {
                try {
                    ensureActive(run);
                    // The SAME lane callback fires for three different phases (see
                    // CatalogDeepSearchService: searchLane, addFastSidecarFallbacksFor
                    // TimedOutSources, and onPreviewDone) - only "catalog_index" and
                    // "catalog_sidecar_timeout_fallback" mean "this source's lane is
                    // done, here is its real candidate count". "preview" fires once PER
                    // CANDIDATE during preview verification (no "count" field at all -
                    // it defaults to 0), which would otherwise overwrite a source's real
                    // "ok" result with a false "empty" a moment later. Confirmed live
                    // against /explore/sector/stream before this filter was added.
                    String phase = String.valueOf(lane.getOrDefault("phase", ""));
                    if (!"catalog_index".equals(phase) && !"catalog_sidecar_timeout_fallback".equals(phase)) {
                        return;
                    }
                    finishedSources.add(source);
                    int candidates = (int) lane.getOrDefault("count", 0);
                    // Real outcome, not a hardcoded "ok": zero hits is a legitimate result
                    // (this source has no data for the query), not a failure - the UI must
                    // not paint it red just because the count is 0.
                    String status = candidates > 0 ? "ok" : "empty";
                    run.sourceTerminalStatuses().put(source, status);
                    sendProgress(
                            emitter,
                            run,
                            "source_finished",
                            Map.of("source", source, "status", status, "candidates", candidates));
                    Map<String, Object> partial = new LinkedHashMap<>(latest.get());
                    partial.put("deep_search_status", "running");
                    partial.put("last_source", source);
                    latest.set(partial);
                    sendEvent(emitter, run, "indicators_update", partial);
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            };
            // A question naming 2+ distinct segments ("stavebnictví nebo autovýroba") must fan
            // discovery out per-segment (see ExploreDiscoveryService#discoverMultiSector) here too -
            // otherwise the streaming path silently keeps the old one-combined-search behavior that
            // lets whichever segment matches catalog titles more literally starve the other one out.
            // See ExploreSectorService#enrichSearchQuestion - grounding the LLM's sector guess
            // against the real catalog (a generic "továrna" resolving to "Zpracovatelský průmysl")
            // only fixes the label shown to the user unless the resolved label is also blended
            // into the actual search text, since discoverWithLanes searches with question text
            // alone and the deterministic intent detection has no way to know "továrna" means
            // manufacturing.
            String searchQuestion = ExploreSectorService.enrichSearchQuestion(prep.question(), prep.sector());
            ExploreDiscoveryService.IndicatorBundle indicators = !prep.sectorSegments().isEmpty()
                    ? exploreDiscoveryService.discoverWithLanesMultiSector(
                            prep.sectorSegments(), request.broaderSearch(), resolvedCountryCodes, laneConsumer)
                    : exploreDiscoveryService.discoverWithLanes(
                            searchQuestion, prep.sector(), request.broaderSearch(), resolvedCountryCodes, laneConsumer);

            ensureActive(run);

            // Sources that never produced source_finished are either:
            // - genuinely cancelled by the shared lane wall-budget (timeout), or
            // - never dispatched at all (foreign-geo / CZ-only skip). The latter must NOT be
            // painted as timeout — that was the false arad/csu/fred timeout pattern on AT queries.
            Set<String> plannedSources = plannedSourcesFromDiscovery(indicators);
            boolean foreignGeo = isForeignGeo(country, prep);
            for (String source : requestedSources) {
                if (finishedSources.contains(source)) {
                    continue;
                }
                boolean planned = plannedSources.isEmpty() || plannedSources.contains(source);
                boolean czOnlySkipped = foreignGeo && ExploreManagerDiscoveryTerms.isCzOnlySource(source);
                if (!planned || czOnlySkipped) {
                    run.sourceTerminalStatuses().put(source, "skipped");
                    sendProgress(
                            emitter,
                            run,
                            "source_skipped",
                            Map.of(
                                    "source",
                                    source,
                                    "status",
                                    "skipped",
                                    "candidates",
                                    0,
                                    "reason",
                                    czOnlySkipped ? "cz_only_source" : "not_planned"));
                    continue;
                }
                run.sourceTerminalStatuses().put(source, "timeout");
                sendProgress(
                        emitter,
                        run,
                        "source_timeout",
                        Map.of("source", source, "status", "timeout", "candidates", 0));
            }

            Map<String, Object> finalBody = new LinkedHashMap<>(
                    sectorService.finalizeAnalysis(prep, indicators, request));
            finalBody.put("partial", false);
            finalBody.put("deep_search_status", "completed");
            finalBody = withRunObservability(finalBody, run, "completed");
            sendEvent(emitter, run, "search_finished", finalBody);
            logCompletion(run, finalBody, "completed");
            run.terminal().set(true);
            emitter.complete();
        } catch (Exception ex) {
            if (run.cancelled().get()
                    || ex instanceof CancellationException
                    || Thread.currentThread().isInterrupted()) {
                log.info(
                        "manager_explorer_discovery status=cancelled request_id={} discovery_run_id={} full_discovery_run_count={} cancellation_reason={} source_terminal_statuses={}",
                        run.requestId(),
                        run.discoveryRunId(),
                        run.fullDiscoveryRunCount().get(),
                        run.cancellationReason().get(),
                        orderedStatuses(run));
                return;
            }
            try {
                run.sourceTerminalStatuses().replaceAll((source, status) -> "running".equals(status) ? "error" : status);
                Map<String, Object> errorPayload = new LinkedHashMap<>();
                errorPayload.put("ok", false);
                errorPayload.put("error", ex.getClass().getSimpleName() + ": " + String.valueOf(ex.getMessage()));
                errorPayload.put("sector_indicators", List.of());
                errorPayload.put("macro_indicators", List.of());
                errorPayload = withRunObservability(errorPayload, run, "error");
                sendEvent(emitter, run, "search_finished", errorPayload);
                log.warn(
                        "manager_explorer_discovery status=error request_id={} discovery_run_id={} full_discovery_run_count={} source_terminal_statuses={}",
                        run.requestId(),
                        run.discoveryRunId(),
                        run.fullDiscoveryRunCount().get(),
                        run.sourceTerminalStatuses(),
                        ex);
                run.terminal().set(true);
                emitter.complete();
            } catch (IOException io) {
                run.terminal().set(true);
                emitter.completeWithError(io);
            }
        } finally {
            run.terminal().set(true);
            heartbeat.shutdownNow();
        }
    }

    private void sendHeartbeat(SseEmitter emitter, DiscoveryRunContext run) {
        try {
            ensureActive(run);
            synchronized (emitter) {
                emitter.send(SseEmitter.event().comment("keep-alive"));
            }
        } catch (Exception ex) {
            cancelRunIfActive(run, "heartbeat_write_failed");
        }
    }

    private void sendEvent(
            SseEmitter emitter, DiscoveryRunContext run, String event, Map<String, Object> payload) throws IOException {
        ensureActive(run);
        Map<String, Object> packet = new LinkedHashMap<>();
        packet.put("event", event);
        packet.put("request_id", run.requestId());
        packet.put("discovery_run_id", run.discoveryRunId());
        packet.put("payload", payload);
        String data = objectMapper.writeValueAsString(packet);
        synchronized (emitter) {
            emitter.send(SseEmitter.event().data(data, MediaType.APPLICATION_JSON));
        }
    }

    private void sendProgress(
            SseEmitter emitter, DiscoveryRunContext run, String event, Map<String, Object> payload) throws IOException {
        ensureActive(run);
        Map<String, Object> packet = new LinkedHashMap<>(payload);
        packet.put("event", event);
        packet.put("request_id", run.requestId());
        packet.put("discovery_run_id", run.discoveryRunId());
        String data = objectMapper.writeValueAsString(packet);
        synchronized (emitter) {
            emitter.send(SseEmitter.event().data(data, MediaType.APPLICATION_JSON));
        }
    }

    private static Map<String, Object> withRunObservability(
            Map<String, Object> body, DiscoveryRunContext run, String terminalStatus) {
        return ExploreTraceEnvelope.enrich(
                body,
                run.requestId(),
                run.discoveryRunId(),
                run.fullDiscoveryRunCount().get(),
                orderedStatuses(run),
                terminalStatus);
    }

    private static Map<String, String> orderedStatuses(DiscoveryRunContext run) {
        Map<String, String> ordered = new LinkedHashMap<>();
        CatalogSourceRegistry.EXPLORE_DISCOVERY_DEFAULT_SOURCES.stream()
                .map(CatalogSourceRegistry::normalizeSearchSource)
                .distinct()
                .forEach(source -> {
                    String status = run.sourceTerminalStatuses().get(source);
                    if (status != null) {
                        ordered.put(source, status);
                    }
                });
        run.sourceTerminalStatuses().forEach(ordered::putIfAbsent);
        return ordered;
    }

    private static void logCompletion(
            DiscoveryRunContext run, Map<String, Object> body, String terminalStatus) {
        log.info(
                "manager_explorer_discovery status={} request_id={} discovery_run_id={} full_discovery_run_count={} cache_hit={} fallback_reason={} serving_time_ms={} cached_compute_time_ms={} source_terminal_statuses={}",
                terminalStatus,
                run.requestId(),
                run.discoveryRunId(),
                run.fullDiscoveryRunCount().get(),
                body.getOrDefault("cache_hit", false),
                body.get("discovery_fallback_reason"),
                body.getOrDefault("serving_time_ms", 0L),
                body.getOrDefault("cached_compute_time_ms", 0L),
                orderedStatuses(run));
    }

    private static void ensureActive(DiscoveryRunContext run) {
        if (run.cancelled().get() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Manager Explorer stream is no longer active");
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> plannedSourcesFromDiscovery(ExploreDiscoveryService.IndicatorBundle indicators) {
        Set<String> planned = new LinkedHashSet<>();
        Object raw = indicators == null ? null : indicators.discoveryProfile().get("planned_sources");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                String source = CatalogSourceRegistry.normalizeSearchSource(String.valueOf(item));
                if (!source.isBlank()) {
                    planned.add(source);
                }
            }
        }
        return planned;
    }

    @SuppressWarnings("unchecked")
    private static boolean isForeignGeo(String requestCountry, ExploreSectorService.PreparedAnalysis prep) {
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        if (requestCountry != null && !requestCountry.isBlank()) {
            for (String part : requestCountry.split("[,;|/]")) {
                String code = part.trim().toUpperCase(Locale.ROOT);
                if (code.length() == 2) {
                    codes.add(code);
                }
            }
        }
        Map<String, Object> ctx = prep == null || prep.ctx() == null ? Map.of() : prep.ctx();
        Object geoRaw = ctx.get("geo");
        if (geoRaw instanceof Map<?, ?> geoMap) {
            Object countryCodes = geoMap.get("country_codes");
            if (countryCodes instanceof List<?> list) {
                for (Object item : list) {
                    String code = String.valueOf(item).trim().toUpperCase(Locale.ROOT);
                    if (code.length() == 2) {
                        codes.add(code);
                    }
                }
            }
        }
        Object understandingCountry = prep == null || prep.understanding() == null
                ? null
                : prep.understanding().get("country");
        if (understandingCountry != null) {
            String code = String.valueOf(understandingCountry).trim().toUpperCase(Locale.ROOT);
            if (code.length() == 2) {
                codes.add(code);
            }
        }
        return !codes.isEmpty() && codes.stream().anyMatch(code -> !"CZ".equals(code));
    }

    private static void cancelRunIfActive(DiscoveryRunContext run, String reason) {
        if (run.terminal().get() || !run.cancelled().compareAndSet(false, true)) {
            return;
        }
        run.cancellationReason().compareAndSet(null, reason);
        Thread worker = run.workerThread().get();
        if (worker != null) {
            worker.interrupt();
        }
    }

    private record DiscoveryRunContext(
            String requestId,
            String discoveryRunId,
            AtomicInteger fullDiscoveryRunCount,
            Map<String, String> sourceTerminalStatuses,
            AtomicBoolean cancelled,
            AtomicBoolean terminal,
            AtomicReference<String> cancellationReason,
            AtomicReference<Thread> workerThread) {
        static DiscoveryRunContext create(String suppliedRequestId) {
            String requestId = suppliedRequestId == null ? "" : suppliedRequestId.strip();
            if (requestId.isBlank() || requestId.length() > 128 || !requestId.matches("[A-Za-z0-9._:-]+")) {
                requestId = UUID.randomUUID().toString();
            }
            return new DiscoveryRunContext(
                    requestId,
                    UUID.randomUUID().toString(),
                    new AtomicInteger(),
                    new ConcurrentHashMap<>(),
                    new AtomicBoolean(),
                    new AtomicBoolean(),
                    new AtomicReference<>(),
                    new AtomicReference<>());
        }
    }
}
