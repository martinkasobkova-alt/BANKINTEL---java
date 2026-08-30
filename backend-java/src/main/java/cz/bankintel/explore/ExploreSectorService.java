package cz.bankintel.explore;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.CatalogSourceRegistry;
import cz.bankintel.explore.ExploreDtos.ExploreSectorRequest;
import cz.bankintel.search.openai.OpenAiClient;
import cz.bankintel.service.research.WebResearchService;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Service
@RequiredArgsConstructor
public class ExploreSectorService {

    private static final Logger log = LoggerFactory.getLogger(ExploreSectorService.class);
    private static final ObjectMapper FALLBACK_MAPPER = new ObjectMapper();

    /** Fallback sector/macro indicators (no OpenAI configured), loaded from JSON at startup. */
    private static final FallbackIndicatorSet FALLBACK_INDICATORS = loadFallbackIndicators();

    private final ExploreGeoCatalog geoCatalog;
    private final ExploreGeoResolver geoResolver;
    private final ExploreQueryUnderstandingService queryUnderstandingService;
    private final OpenAiClient openAiClient;
    private final ObjectMapper objectMapper;
    private final ExploreDiscoveryService exploreDiscoveryService;
    private final ExplorePresetPreviewService presetPreviewService;
    private final ExploreDiscoveryCache discoveryCache;
    private final Environment environment;
    private final WebResearchService webResearchService;

    public Map<String, Object> analyzeSector(ExploreSectorRequest request) {
        PreparedAnalysis prep = prepareAnalysis(request);
        if (prep.shortCircuit() != null) {
            return prep.shortCircuit();
        }
        boolean isolatedColdPath = isIsolatedColdPath(request);
        List<String> resolvedCountryCodes = ExploreGeoResolver.countryCodesFrom(prep.ctx().get("geo"));
        String searchQuestion = enrichSearchQuestion(prep.question(), prep.sector());
        ExploreDiscoveryService.IndicatorBundle indicators = !prep.sectorSegments().isEmpty()
                ? exploreDiscoveryService.discoverMultiSector(
                        prep.sectorSegments(), request.broaderSearch(), resolvedCountryCodes, isolatedColdPath)
                : isolatedColdPath
                        ? exploreDiscoveryService.discoverUncachedForProfile(
                                searchQuestion, prep.sector(), request.broaderSearch(), resolvedCountryCodes)
                        : exploreDiscoveryService.discover(
                                searchQuestion, prep.sector(), request.broaderSearch(), resolvedCountryCodes);
        return finalizeAnalysis(prep, indicators, request);
    }

    /**
     * Resolves query understanding, sector/geo, and the empty contract base WITHOUT running
     * discovery. Exists so a caller that needs its own discovery progress callbacks (the SSE
     * stream in {@link ExploreStreamService}) can run discovery exactly once - via {@link
     * ExploreDiscoveryService#discoverWithLanes} - and merge the result with {@link
     * #finalizeAnalysis}, instead of {@link #analyzeSector} redundantly calling {@link
     * ExploreDiscoveryService#discover} a second time for the same request.
     */
    public PreparedAnalysis prepareAnalysis(ExploreSectorRequest request) {
        long understandingStarted = System.currentTimeMillis();
        String question = request.question() == null ? "" : request.question().trim();
        boolean queryOnly = request.queryOnly() || (!question.isBlank() && request.sector().isBlank());

        Map<String, Object> understanding = queryUnderstandingService.understand(
                question,
                request.sector(),
                request.country(),
                request.geoMode(),
                request.continent(),
                queryOnly);
        long understandingMs = System.currentTimeMillis() - understandingStarted;

        String sector = firstNonBlank(request.sector(), str(understanding.get("sector")));
        // Only meaningful when the LLM inferred the sector (no explicit single-value UI
        // selection): a genuine multi-item array means the question names 2+ distinct segments
        // ("stavebnictví nebo autovýroba") - discovery must fan out per segment instead of one
        // combined free-text search, or the segment whose Czech label matches catalog titles more
        // literally crowds the other one out of the shared per-source result budget (confirmed
        // live: "stavebnictví" returned 12 rows, "autovýroba" returned 0).
        List<String> sectorSegments =
                request.sector().isBlank() ? sectorSegmentsFrom(understanding.get("sector")) : List.of();
        if (sector.isBlank() && question.isBlank()) {
            return PreparedAnalysis.shortCircuit(Map.of(
                    "ok", false,
                    "error", "Zadejte dotaz — segment a zemi aplikace odvodí automaticky.",
                    "sector_indicators", List.of(),
                    "macro_indicators", List.of()));
        }

        if (request.suggestionsOnly()) {
            if (sector.isBlank()) {
                return PreparedAnalysis.shortCircuit(Map.of(
                        "ok", false,
                        "error", "Chybí segment — zadejte dotaz nebo vyberte segment.",
                        "suggestions", List.of()));
            }
            return PreparedAnalysis.shortCircuit(Map.of("ok", true, "suggestions", relatedSuggestions(sector)));
        }

        String country =
                firstNonBlank(request.country(), request.countriesAsCsv(), str(understanding.get("country")));
        String geoMode = firstNonBlank(request.geoMode(), str(understanding.get("geo_mode")));
        String continent = firstNonBlank(request.continent(), str(understanding.get("continent")));

        Map<String, Object> managerPreset = geoCatalog.findSectorByIdOrLabel(sector);
        if (managerPreset.isEmpty() && understanding.get("sector_id") != null) {
            managerPreset = geoCatalog.findSectorByIdOrLabel(str(understanding.get("sector_id")));
        }
        // A multi-segment question ("stavebnictví nebo autovýroba") must keep showing BOTH names -
        // sector_id from the LLM is a single id even when sector was a 2+ item array (it names one
        // "primary" segment), so resolving a managerPreset from that single id must not overwrite
        // the already-joined multi-segment display string with just that one preset's label.
        // Confirmed live: displayed "sector" silently dropped to "Automobilový průmysl" alone even
        // though the search itself correctly covered stavebnictví too.
        if (!managerPreset.isEmpty() && sectorSegments.isEmpty()) {
            sector = str(managerPreset.get("label_cs"));
        }

        Map<String, Object> geo = geoResolver.resolve(country, geoMode, continent);
        Map<String, Object> ctx = ExploreSectorContract.buildContext(
                sector, question.isBlank() ? sector : question, geo, managerPreset, understanding);
        // Špatný výsledek, který vypadá správně, je horší než chyba: když jsme otázku pochopili
        // jako „o konkrétní zemi", ale rozsah analýzy skončil na „svět", řekneme to nahlas.
        String understoodGeoMode = str(understanding.get("geo_mode"));
        String resolvedGeoMode = str(geo.get("mode"));
        if (!understoodGeoMode.isBlank()
                && !"none".equals(understoodGeoMode)
                && "none".equals(resolvedGeoMode)) {
            ctx.put(
                    "geo_warnings",
                    List.of("Dotaz jsme pochopili jako geograficky omezený (" + understoodGeoMode
                            + "), ale žádnou zemi se nepodařilo určit — analýza proto běží pro celý svět."));
        }
        Map<String, Object> base = ExploreSectorContract.buildEmptyContract(ctx, "catalog_deep_search", false);

        return new PreparedAnalysis(null, sector, question, ctx, base, understanding, understandingMs, sectorSegments);
    }

    /**
     * Merges an already-computed discovery result into the final response body. If discovery
     * found nothing, falls back to the AI/JSON indicator suggestion path - same rule {@link
     * #analyzeSector} has always applied.
     *
     * <p>ETAPA 6 live finding: {@link #discoverIndicators} calls the OpenAI chat completion API
     * directly with a freeform-JSON prompt and NO caching of its own - for a query whose catalog
     * search genuinely returns nothing (so this fallback is what actually answers it every time),
     * that made the response just as unstable as the catalog planner ever was, even after the
     * discovery cache fix below. Confirmed live for "ziskovost pojistovnictvi ve Spanelsku": every
     * one of 10 identical repeats produced a different suggested indicator set. The fallback is
     * cached the same way discovery results are - same key, same TTL - so a repeat of a
     * catalog-empty query gets the same suggested indicators too, instead of a fresh LLM call.
     */
    public Map<String, Object> finalizeAnalysis(
            PreparedAnalysis prep, ExploreDiscoveryService.IndicatorBundle indicators, ExploreSectorRequest request) {
        boolean catalogDiscoveryEmpty =
                indicators.sectorIndicators().isEmpty() && indicators.macroIndicators().isEmpty();
        List<Map<String, Object>> webSources = List.of();
        String webResearchStatus = "not_attempted";
        if (catalogDiscoveryEmpty) {
            ExploreDiscoveryService.IndicatorBundle discovery = indicators;
            ExploreDiscoveryService.IndicatorBundle fallback = isIsolatedColdPath(request)
                    ? discoverUncachedFallbackIndicators(prep, request.broaderSearch())
                    : cachedFallbackIndicators(prep, request.broaderSearch());
            Map<String, Object> profile = new LinkedHashMap<>(discovery.discoveryProfile());
            profile.put("fallback_used", true);
            profile.put("fallback_reason", "catalog_discovery_empty");
            profile.put("fallback_cache_hit", fallback.cacheHit());
            profile.put("fallback_serving_ms", fallback.servingTimeMs());
            profile.put("fallback_candidate_count", fallback.totalCandidates());
            profile.put("fallback_preview_count", 0);
            profile.put("fallback_validator_outcome", "not_run");
            profile.put(
                    "fallback_terminal_status",
                    fallback.sectorIndicators().isEmpty() && fallback.macroIndicators().isEmpty()
                            ? "empty"
                            : "suggestions_generated");
            profile.put("fallback_source_routing", fallbackSources(fallback));
            indicators = new ExploreDiscoveryService.IndicatorBundle(
                    fallback.sectorIndicators(),
                    fallback.macroIndicators(),
                    fallback.totalCandidates(),
                    discovery.cacheHit() && fallback.cacheHit(),
                    discovery.servingTimeMs() + fallback.servingTimeMs(),
                    discovery.cachedComputeTimeMs() + fallback.cachedComputeTimeMs(),
                    profile);

            // Web research is a distinct, additional step from the AI-suggestion fallback above -
            // that fallback only ever proposes indicator NAMES to look at, it never has an actual
            // figure. Confirmed live: "Jakým významem se Praha podílí na HDP Česka?" has no ČSÚ
            // regional-GDP series in the catalog at all, so the only way to answer the question is
            // to look outside it. A failure here must never fail the whole report - it only ever
            // means the new "Zjištění z webu" section stays empty.
            try {
                Map<String, Object> webResult = webResearchService.researchSectorContext(
                        str(prep.ctx().get("manager_question")), sectorWebContext(prep));
                webSources = castFindingsList(webResult.get("findings"));
                webResearchStatus = webSources.isEmpty() ? "empty" : "found";
            } catch (Exception ex) {
                log.warn("Manager Explorer web-research fallback failed: {}", ex.getMessage());
                webResearchStatus = "failed";
            }
        }
        Map<String, Object> out = ExploreSectorContract.mergeIndicators(
                prep.base(),
                indicators.sectorIndicators(),
                indicators.macroIndicators(),
                indicators.totalCandidates(),
                "completed",
                false);
        out = ExploreSectorContract.mergeWebSources(out, webSources, webResearchStatus);
        out.put("query_understanding", prep.understanding());
        out.put("analysis_scope", normalizeScope(request.analysisScope()));
        out.put("user_data_privacy_mode", normalizePrivacy(request.userDataPrivacyMode()));
        out.put("company_indicators", List.of());
        out.put("company_vs_market", List.of());
        out.put("user_data_used", false);
        out.put("user_uploads_used", List.of());
        out.put(
                "discovery_source",
                catalogDiscoveryEmpty ? "catalog_deep_search_with_general_fallback" : "catalog_deep_search");
        // Additive fields (ETAPA 6): a cache hit must never present the ORIGINAL computation's
        // latency as if it were the cost of THIS request - cache_hit/serving_time_ms tell the
        // truth about what this particular call actually cost.
        out.put("cache_hit", indicators.cacheHit());
        out.put("serving_time_ms", indicators.servingTimeMs());
        out.put("cached_compute_time_ms", indicators.cachedComputeTimeMs());
        Map<String, Object> performanceProfile = new LinkedHashMap<>(indicators.discoveryProfile());
        performanceProfile.put("query_understanding_ms", prep.queryUnderstandingMs());
        performanceProfile.put("manager_explorer_serving_ms", indicators.servingTimeMs());
        performanceProfile.put("cache_hit", indicators.cacheHit());
        out.put("performance_profile", performanceProfile);
        out.put("discovery_fallback_reason", catalogDiscoveryEmpty ? "catalog_discovery_empty" : null);
        return out;
    }

    /** Bounded context passed to {@link WebResearchService#researchSectorContext} - deliberately
     * just sector/geo/intent, not the full contract, matching {@code WebResearchService}'s own
     * "bounded context" convention for web-search inputs. */
    private static Map<String, Object> sectorWebContext(PreparedAnalysis prep) {
        Map<String, Object> geo = castMap(prep.ctx().get("geo"));
        Map<String, Object> understanding = prep.understanding();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sector", prep.sector());
        out.put("country", str(prep.ctx().get("country_for_query")));
        out.put("geo_display", str(geo.get("display")));
        out.put("intent_summary", understanding == null ? "" : str(understanding.get("intent_summary")));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castFindingsList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                out.add((Map<String, Object>) map);
            }
        }
        return out;
    }

    private static List<String> fallbackSources(ExploreDiscoveryService.IndicatorBundle fallback) {
        List<String> sources = new ArrayList<>();
        for (Map<String, Object> row : fallback.sectorIndicators()) {
            addCanonicalSource(sources, row.get("source"));
        }
        for (Map<String, Object> row : fallback.macroIndicators()) {
            addCanonicalSource(sources, row.get("source"));
        }
        return List.copyOf(sources);
    }

    private static void addCanonicalSource(List<String> sources, Object rawSource) {
        String source = CatalogSourceRegistry.normalizeSearchSource(str(rawSource));
        if (!source.isBlank() && !sources.contains(source)) {
            sources.add(source);
        }
    }

    /**
     * @param shortCircuit non-null when {@link #prepareAnalysis} determined the request should
     *     short-circuit (missing input, suggestions-only) - callers must return it as-is and
     *     skip discovery entirely.
     */
    public record PreparedAnalysis(
            Map<String, Object> shortCircuit,
            String sector,
            String question,
            Map<String, Object> ctx,
            Map<String, Object> base,
            Map<String, Object> understanding,
            long queryUnderstandingMs,
            List<String> sectorSegments) {
        public PreparedAnalysis {
            sectorSegments = sectorSegments == null ? List.of() : List.copyOf(sectorSegments);
        }

        public PreparedAnalysis(
                Map<String, Object> shortCircuit,
                String sector,
                String question,
                Map<String, Object> ctx,
                Map<String, Object> base,
                Map<String, Object> understanding) {
            this(shortCircuit, sector, question, ctx, base, understanding, 0L, List.of());
        }

        public PreparedAnalysis(
                Map<String, Object> shortCircuit,
                String sector,
                String question,
                Map<String, Object> ctx,
                Map<String, Object> base,
                Map<String, Object> understanding,
                long queryUnderstandingMs) {
            this(shortCircuit, sector, question, ctx, base, understanding, queryUnderstandingMs, List.of());
        }

        static PreparedAnalysis shortCircuit(Map<String, Object> result) {
            return new PreparedAnalysis(result, "", "", Map.of(), Map.of(), Map.of(), 0L, List.of());
        }
    }

    public Map<String, Object> buildPresetPreview(
            String sector,
            String question,
            String country,
            String geoMode,
            String continent) {
        String resolvedSector = sector == null ? "" : sector.trim();
        String resolvedQuestion = question == null ? "" : question.trim();
        Map<String, Object> understanding = queryUnderstandingService.understand(
                resolvedQuestion, resolvedSector, country, geoMode, continent, false);
        resolvedSector = firstNonBlank(resolvedSector, str(understanding.get("sector")));
        Map<String, Object> managerPreset = geoCatalog.findSectorByIdOrLabel(resolvedSector);
        if (!managerPreset.isEmpty()) {
            resolvedSector = str(managerPreset.get("label_cs"));
        }
        Map<String, Object> geo = geoResolver.resolve(country, geoMode, continent);
        Map<String, Object> ctx = ExploreSectorContract.buildContext(
                resolvedSector,
                resolvedQuestion.isBlank() ? resolvedSector : resolvedQuestion,
                geo,
                managerPreset,
                understanding);
        ctx.put("analysis_mode", "sector");
        return presetPreviewService.buildJsonSeedPreview(ctx, false);
    }

    private List<Map<String, Object>> relatedSuggestions(String sector) {
        Map<String, Object> preset = geoCatalog.findSectorByIdOrLabel(sector);
        String label = str(preset.getOrDefault("label_cs", sector));
        return List.of(
                Map.of(
                        "sector_id", preset.getOrDefault("id", "related"),
                        "sector_name_cs", label,
                        "reason", "Související segment pro hlubší analýzu",
                        "weight", 0.7));
    }

    private ExploreDiscoveryService.IndicatorBundle cachedFallbackIndicators(PreparedAnalysis prep, boolean broaderSearch) {
        long t0 = System.currentTimeMillis();
        // Prefixed so this can never collide with ExploreDiscoveryCache's OWN entry for the same
        // query (that entry legitimately caches an EMPTY catalog result - this one caches the
        // LLM's freeform suggestion for that same empty case, a different computation).
        String key = "fallback:" + discoveryCache.buildKey(prep.question(), prep.sector(), broaderSearch);
        var cached = discoveryCache.get(key);
        if (cached.isPresent()) {
            var entry = cached.get();
            return new ExploreDiscoveryService.IndicatorBundle(
                    entry.sectorIndicators(),
                    entry.macroIndicators(),
                    entry.totalCandidates(),
                    true,
                    System.currentTimeMillis() - t0,
                    entry.computeTimeMs());
        }
        ExploreDiscoveryService.IndicatorBundle fresh = discoverIndicators(prep.ctx(), broaderSearch);
        long computeTimeMs = System.currentTimeMillis() - t0;
        discoveryCache.put(
                key,
                fresh.sectorIndicators(),
                fresh.macroIndicators(),
                fresh.totalCandidates(),
                computeTimeMs);
        return fresh.withTiming(false, computeTimeMs, computeTimeMs);
    }

    private ExploreDiscoveryService.IndicatorBundle discoverUncachedFallbackIndicators(
            PreparedAnalysis prep, boolean broaderSearch) {
        long started = System.currentTimeMillis();
        ExploreDiscoveryService.IndicatorBundle fresh = discoverIndicators(prep.ctx(), broaderSearch);
        long computeTimeMs = System.currentTimeMillis() - started;
        return fresh.withTiming(false, computeTimeMs, computeTimeMs);
    }

    private boolean isIsolatedColdPath(ExploreSectorRequest request) {
        return request.forceLiveDeepSearch()
                && environment.acceptsProfiles(Profiles.of("cold-path-profile"));
    }

    private ExploreDiscoveryService.IndicatorBundle discoverIndicators(Map<String, Object> ctx, boolean broaderSearch) {
        String sector = str(ctx.get("sector"));
        String question = str(ctx.get("manager_question"));
        Map<String, Object> geo = castMap(ctx.get("geo"));
        if (openAiClient.isConfigured()) {
            try {
                String system =
                        """
                        Jsi datový kurátor pro manažerský Explorer. Vrať pouze JSON:
                        {"sector_indicators":[{"source":"eurostat|ecb|fred|imf|oecd4","dataset_id":"...","indicator_name":"...","manager_category":"sector_indicators","confidence_score":0.0-1.0}],
                         "macro_indicators":[{"source":"...","dataset_id":"...","indicator_name":"...","manager_category":"macro_indicators","confidence_score":0.0-1.0}]}
                        Navrhni 4-8 sektorových a 2-4 makro ukazatelů relevantních k dotazu. Používej reálné katalogové zdroje.
                        """;
                String userPrompt =
                        "Segment: " + sector + "\nDotaz: " + question + "\nGeo: " + geo.get("display") + "\nŠirší hledání: "
                                + broaderSearch;
                JsonNode response = openAiClient.chatCompletion(system, userPrompt);
                String content = extractContent(response);
                Map<String, Object> parsed = objectMapper.readValue(content, new TypeReference<>() {});
                List<Map<String, Object>> sectorRows = castIndicatorList(parsed.get("sector_indicators"), sector);
                List<Map<String, Object>> macroRows = castIndicatorList(parsed.get("macro_indicators"), sector);
                return new ExploreDiscoveryService.IndicatorBundle(
                        sectorRows, macroRows, sectorRows.size() + macroRows.size(), false, 0L);
            } catch (Exception ignored) {
                // fall through
            }
        }
        return fallbackIndicators(sector);
    }

    private ExploreDiscoveryService.IndicatorBundle fallbackIndicators(String sector) {
        List<Map<String, Object>> sectorRows = new ArrayList<>();
        for (FallbackIndicator fi : FALLBACK_INDICATORS.sectorIndicators()) {
            sectorRows.add(indicator(fi.source(), fi.datasetId(), fi.nameFor(sector), fi.category(), fi.confidenceScore()));
        }
        List<Map<String, Object>> macroRows = new ArrayList<>();
        for (FallbackIndicator fi : FALLBACK_INDICATORS.macroIndicators()) {
            macroRows.add(indicator(fi.source(), fi.datasetId(), fi.nameFor(sector), fi.category(), fi.confidenceScore()));
        }
        return new ExploreDiscoveryService.IndicatorBundle(
                sectorRows, macroRows, sectorRows.size() + macroRows.size(), false, 0L);
    }

    private static Map<String, Object> indicator(
            String source, String datasetId, String name, String category, double confidence) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("source", source);
        row.put("dataset_id", datasetId);
        row.put("indicator_name", name);
        row.put("title", name);
        row.put("manager_category", category);
        row.put("confidence_score", confidence);
        row.put("from_preset", false);
        return row;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castIndicatorList(Object value, String sector) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> row = new LinkedHashMap<>((Map<String, Object>) map);
                row.putIfAbsent("indicator_name", row.getOrDefault("title", "Ukazatel — " + sector));
                row.putIfAbsent("title", row.get("indicator_name"));
                row.putIfAbsent("manager_category", "sector_indicators");
                out.add(row);
            }
        }
        return out;
    }

    private static String extractContent(JsonNode response) throws Exception {
        JsonNode content = response.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.asText().isBlank()) {
            throw new IllegalStateException("OpenAI returned empty content");
        }
        String text = content.asText().trim();
        if (text.startsWith("```")) {
            int start = text.indexOf('\n');
            int end = text.lastIndexOf("```");
            if (start >= 0 && end > start) {
                text = text.substring(start + 1, end).trim();
            }
        }
        return text;
    }

    private static String normalizeScope(String value) {
        String raw = value == null ? "" : value.trim().toLowerCase();
        return "selected_only".equals(raw) ? "selected_only" : "auto";
    }

    private static String normalizePrivacy(String value) {
        String raw = value == null ? "" : value.trim().toLowerCase();
        if (raw.contains("safe") || raw.contains("anonym")) {
            return "private_safe_summary";
        }
        return "private_local_only";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    /**
     * True multi-item list (2+ distinct, non-blank entries) means the LLM understood the question
     * as naming several segments to compare - {@code List.of()} otherwise (including a single-item
     * list, a bare string, or null), so the caller's existing single-sector path is untouched for
     * the overwhelming majority of questions that only ever name one segment.
     */
    private static List<String> sectorSegmentsFrom(Object value) {
        if (!(value instanceof List<?> list) || list.size() < 2) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            String part = item == null ? "" : String.valueOf(item).trim();
            if (!part.isBlank() && !out.contains(part)) {
                out.add(part);
            }
        }
        return out.size() >= 2 ? List.copyOf(out) : List.of();
    }

    /**
     * Appends the resolved canonical segment label to the raw question text used for discovery -
     * NOT to {@code prep.question()} itself, which stays the user's literal words for display.
     * Grounding the LLM's sector guess against the real catalog (see {@link
     * ExploreQueryUnderstandingService#understand}) fixes the label shown to the user for a
     * generic term like "továrna", but {@link ExploreDiscoveryService#discover} still searches
     * with the raw question text alone, and the deterministic keyword-based intent detection
     * ({@code ExploreManagerDiscoveryTerms}) has no way to know "továrna" means manufacturing -
     * confirmed live: "továrna" alone activated no production intent, so the actual search never
     * got the industrial-production probe terms/preview seeds that make "výroba"/"stavebnictví"
     * queries work well. Appending the resolved label ("Zpracovatelský průmysl") gives the SAME
     * deterministic matching a real word to latch onto, without hardcoding "továrna" (or every
     * other synonym a user might type) as a keyword anywhere.
     */
    static String enrichSearchQuestion(String question, String sector) {
        if (question == null || question.isBlank() || sector == null || sector.isBlank()) {
            return question;
        }
        if (question.toLowerCase(java.util.Locale.ROOT).contains(sector.toLowerCase(java.util.Locale.ROOT))) {
            return question;
        }
        return question + " " + sector;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    /**
     * The query-understanding LLM returns {@code country}/{@code sector} as a bare string for a
     * single-item question, but as a JSON array for a multi-item one ("Německo nebo Itálii",
     * "stavebnictví nebo autovýroba"). {@code String.valueOf(List)} would render that array as its
     * Java {@code toString()} - e.g. {@code "[DE, IT]"} - which {@link ExploreGeoResolver#resolve}
     * cannot parse as country codes (its comma-split leaves bracket characters attached to the
     * tokens). Joining list elements with {@code ", "} instead produces {@code "DE, IT"}, which
     * resolves correctly, and reads naturally when used as a display label (multi-sector).
     */
    private static String str(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object item : list) {
                String part = item == null ? "" : String.valueOf(item).trim();
                if (!part.isBlank() && !parts.contains(part)) {
                    parts.add(part);
                }
            }
            return String.join(", ", parts);
        }
        return String.valueOf(value).trim();
    }

    private record FallbackIndicator(
            @JsonProperty("source") String source,
            @JsonProperty("dataset_id") String datasetId,
            @JsonProperty("name_template") String nameTemplate,
            @JsonProperty("category") String category,
            @JsonProperty("confidence_score") double confidenceScore) {

        String nameFor(String sector) {
            return nameTemplate == null ? "" : nameTemplate.replace("{sector}", sector == null ? "" : sector);
        }
    }

    private record FallbackIndicatorSet(
            List<FallbackIndicator> sectorIndicators, List<FallbackIndicator> macroIndicators) {

        static FallbackIndicatorSet empty() {
            return new FallbackIndicatorSet(List.of(), List.of());
        }
    }

    private static FallbackIndicatorSet loadFallbackIndicators() {
        try (InputStream in = ExploreSectorService.class.getResourceAsStream(
                "/catalog/explore_fallback_indicators.json")) {
            if (in == null) {
                return FallbackIndicatorSet.empty();
            }
            Map<String, List<FallbackIndicator>> raw = FALLBACK_MAPPER.readValue(
                    in, new TypeReference<LinkedHashMap<String, List<FallbackIndicator>>>() {});
            List<FallbackIndicator> sectorIndicators =
                    raw.getOrDefault("sector_indicators", List.of());
            List<FallbackIndicator> macroIndicators =
                    raw.getOrDefault("macro_indicators", List.of());
            return new FallbackIndicatorSet(List.copyOf(sectorIndicators), List.copyOf(macroIndicators));
        } catch (Exception ex) {
            return FallbackIndicatorSet.empty();
        }
    }
}
