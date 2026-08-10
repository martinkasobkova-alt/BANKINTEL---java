package cz.bankintel.sources.eurostat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class EurostatDimensionService {

    private static final Logger log = LoggerFactory.getLogger(EurostatDimensionService.class);
    private static final URI BASE = URI.create("https://ec.europa.eu/eurostat/api/dissemination/statistics/1.0/data");
    private static final Set<String> SKIP_DIM_KEYS = Set.of(
            "time", "format", "lang", "json", "query_mode", "geo_scope",
            "lasttimeperiod", "sincetimeperiod", "untiltimeperiod", "startperiod", "endperiod");
    private static final List<String> PRIMARY_ORDER = List.of("geo", "ref_area", "country", "freq", "unit");

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(22)).build();
    private final ConcurrentHashMap<String, CachedMeta> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Map<String, Object>> resolvedPreviewCache = new ConcurrentHashMap<>();
    /** Bounded, I/O-bound live-probe fan-out — virtual threads so probes for a dimension run
     * concurrently instead of sequentially (cold-start dimension resolution was ~8 serial round
     * trips per dimension, ~25s; running them in parallel bounds it to ~1 round trip). */
    private final ExecutorService probeExecutor = Executors.newVirtualThreadPerTaskExecutor();
    /** Global cap on concurrent outbound Eurostat HTTP calls (across ALL datasets/candidates being
     * previewed at once, AND the Manager Explorer cache-refresh batch job - see {@link
     * EurostatRateLimiter}). Per-candidate/per-dimension parallelism is cheap, but multiple
     * deep-search preview candidates resolving dimensions at the same time can otherwise fan out
     * to dozens of simultaneous requests and trip Eurostat's own rate limiting, making every
     * request slow again. */
    private final EurostatRateLimiter eurostatCallLimiter;

    @PreDestroy
    void shutdownExecutors() {
        probeExecutor.shutdown();
    }

    public Map<String, Object> dimensionAvailability(String datasetId, Map<String, Object> body) {
        String sid = cleanDatasetId(datasetId);
        Map<String, String> selected = normalizeSelected(body.get("selected_dimensions"));
        String target = String.valueOf(body.getOrDefault("target_dimension", "")).trim();
        if (!target.isBlank()) {
            return availableOptions(sid, selected, target);
        }
        return cascadeState(sid, selected);
    }

    public Map<String, Object> previewAvailableDimensions(String datasetId) {
        String sid = cleanDatasetId(datasetId);
        Map<String, Object> meta = fetchMetadata(sid);
        if (meta == null) {
            return Map.of();
        }
        Map<String, Map<String, Object>> dims = metadataDimensions(meta);
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : dims.entrySet()) {
            String field = entry.getKey();
            if (field == null || field.isBlank() || SKIP_DIM_KEYS.contains(field.toLowerCase(Locale.ROOT))) {
                continue;
            }
            Map<String, Object> spec = new LinkedHashMap<>(entry.getValue());
            Object rawValues = spec.get("values");
            List<String> values = new ArrayList<>();
            if (rawValues instanceof List<?> list) {
                for (Object value : list) {
                    String code = String.valueOf(value).trim();
                    if (!code.isBlank()) {
                        values.add(code);
                    }
                    if (values.size() >= 500) {
                        break;
                    }
                }
            }
            if (values.isEmpty()) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, String> labels =
                    spec.get("value_labels") instanceof Map<?, ?> rawLabels
                            ? (Map<String, String>) rawLabels
                            : Map.of();
            List<Map<String, String>> options = new ArrayList<>();
            for (String code : values) {
                options.add(Map.of("code", code, "label", labels.getOrDefault(code, code)));
            }
            spec.put("values", values);
            spec.put("sample_values", values);
            spec.put("sample_options", options);
            out.put(field, spec);
        }
        return out;
    }

    /**
     * Resolve preview query params for a multidimensional dataset — existence probe with
     * {@code lastTimePeriod=1} (port Python {@code live_probe} / deep-search preview).
     */
    public Map<String, Object> resolvePreviewQueryParams(String datasetId, String preferredGeo) {
        String sid = cleanDatasetId(datasetId);
        String preferredGeoCode = preferredGeo != null && !preferredGeo.isBlank()
                ? preferredGeo.trim().toUpperCase(Locale.ROOT)
                : "";
        String cacheKey = sid + "|" + preferredGeoCode;
        Map<String, Object> cachedResolved = resolvedPreviewCache.get(cacheKey);
        if (cachedResolved != null) {
            return cachedResolved;
        }
        Map<String, Object> meta = fetchMetadata(sid);
        if (meta == null) {
            return Map.of();
        }
        Map<String, Map<String, Object>> dims = metadataDimensions(meta);
        if (dims.isEmpty()) {
            return Map.of();
        }

        Map<String, String> selection = resolveFastSelection(sid, dims, preferredGeo);
        if (selection.isEmpty()) {
            selection = resolveViaFullCascade(sid, dims, preferredGeo);
        }
        if (selection.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> qp = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : selection.entrySet()) {
            qp.put(entry.getKey(), entry.getValue());
        }
        qp.put("format", "JSON");
        qp.put("lang", "EN");
        qp.put("lastTimePeriod", "1");
        resolvedPreviewCache.put(cacheKey, qp);
        return qp;
    }

    /**
     * Fast preview-selection path — try plain metadata defaults (one live probe) first, then a
     * bounded greedy geo/dimension refinement (a handful of probes with early exit), before the
     * caller falls back to the exhaustive per-dimension cascade. For most datasets this replaces
     * ~8 sequential probes per dimension with 1-3 probes total, which is the main contributor to
     * eurostat cold-start latency (kolo 6, Zjištění C).
     */
    private Map<String, String> resolveFastSelection(
            String datasetId, Map<String, Map<String, Object>> dims, String preferredGeo) {
        Map<String, String> defaults = buildDefaultSelection(dims, preferredGeo);
        if (!defaults.isEmpty() && probeCombinationHasData(datasetId, defaults)) {
            return defaults;
        }
        Map<String, String> refined = refineSelectionByProbe(datasetId, dims, defaults, preferredGeo);
        if (!refined.isEmpty() && probeCombinationHasData(datasetId, refined)) {
            return refined;
        }
        return Map.of();
    }

    /** Exhaustive per-dimension live-probe cascade (parallelized) — thorough fallback when the fast path fails. */
    private Map<String, String> resolveViaFullCascade(
            String datasetId, Map<String, Map<String, Object>> dims, String preferredGeo) {
        Map<String, String> seed = new LinkedHashMap<>();
        for (String geoKey : List.of("geo", "ref_area", "country")) {
            if (dims.containsKey(geoKey)) {
                pickDefault(seed, dims, geoKey, geoPreferenceList(preferredGeo));
                break;
            }
        }
        Map<String, Object> cascade = cascadeState(datasetId, seed, 8);
        if (Boolean.TRUE.equals(cascade.get("empty_combination"))) {
            return Map.of();
        }
        Object selectedObj = cascade.get("selected_dimensions");
        if (!(selectedObj instanceof Map<?, ?> selectedMap)) {
            return Map.of();
        }
        Map<String, String> selection = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : selectedMap.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                String key = String.valueOf(entry.getKey()).trim();
                String value = String.valueOf(entry.getValue()).trim();
                if (!key.isBlank() && !value.isBlank()) {
                    selection.put(key, value);
                }
            }
        }
        if (selection.isEmpty() || !probeCombinationHasData(datasetId, selection)) {
            return Map.of();
        }
        return selection;
    }

    public boolean combinationHasData(String datasetId, Map<String, String> selected) {
        return probeCombinationHasData(cleanDatasetId(datasetId), selected);
    }

    public Map<String, Object> dimensionDefaults(String datasetId, Map<String, Object> body) {
        String sid = cleanDatasetId(datasetId);
        Map<String, Object> meta = fetchMetadata(sid);
        if (meta == null) {
            return Map.of(
                    "dataset_id", sid,
                    "selectedDimensions", Map.of(),
                    "selection", Map.of(),
                    "safe", false,
                    "verified", false,
                    "confidence", "low",
                    "reason", "Metadata datasetu není dostupná.",
                    "source", "fallback",
                    "warnings", List.of("metadata_unavailable"));
        }
        Map<String, Map<String, Object>> dims = metadataDimensions(meta);
        Map<String, String> selection = new LinkedHashMap<>();
        pickDefault(selection, dims, "geo", List.of("CZ", "DE", "EU27_2020", "EU28"));
        pickDefault(selection, dims, "freq", List.of("A", "Q", "M"));
        pickDefault(selection, dims, "unit", List.of("PC", "PCH", "EUR", "INDEX"));
        for (String key : dims.keySet()) {
            if (!selection.containsKey(key) && !SKIP_DIM_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                List<Map<String, String>> options = buildOptions(dims.get(key));
                if (options.size() == 1) {
                    selection.put(key, options.get(0).get("code"));
                }
            }
        }
        return Map.of(
                "dataset_id", sid,
                "selectedDimensions", selection,
                "selection", selection,
                "safe", !selection.isEmpty(),
                "verified", false,
                "confidence", selection.isEmpty() ? "low" : "medium",
                "reason", "Vybráno podle nejběžnější dostupné kombinace pro tento dataset.",
                "source", "metadata_rule",
                "warnings", List.of());
    }

    private Map<String, Object> cascadeState(String datasetId, Map<String, String> selected) {
        return cascadeState(datasetId, selected, 24);
    }

    private Map<String, Object> cascadeState(String datasetId, Map<String, String> selected, int maxProbesPerDim) {
        Map<String, Object> meta = fetchMetadata(datasetId);
        if (meta == null) {
            return Map.of(
                    "dataset_id", datasetId,
                    "selected_dimensions", selected,
                    "dimensions", List.of(),
                    "invalid_removed", List.of(),
                    "empty_combination", true,
                    "availability_mode", "latest_only");
        }
        Map<String, Map<String, Object>> dims = metadataDimensions(meta);
        List<String> order = orderedDimensionKeys(dims);
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> invalidRemoved = new ArrayList<>();
        Map<String, String> workingSelected = new LinkedHashMap<>(selected);
        for (String field : order) {
            if (SKIP_DIM_KEYS.contains(field.toLowerCase(Locale.ROOT))) {
                continue;
            }
            List<Map<String, String>> options = buildOptions(dims.get(field));
            if (options.isEmpty()) {
                continue;
            }
            ProbeFilterResult filtered = filterOptionsByLiveProbe(datasetId, workingSelected, field, options, maxProbesPerDim);
            options = filtered.options();
            invalidRemoved.addAll(filtered.removed());
            String sel = workingSelected.getOrDefault(field, options.isEmpty() ? "" : options.get(0).get("code"));
            if (sel != null && !sel.isBlank()) {
                String current = sel;
                if (options.stream().noneMatch(o -> current.equals(o.get("code")))) {
                    sel = options.isEmpty() ? "" : options.get(0).get("code");
                }
            }
            if (!sel.isBlank()) {
                workingSelected.put(field, sel);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("field", field);
            row.put("label", String.valueOf(dims.get(field).getOrDefault("label", field)));
            row.put("tier", PRIMARY_ORDER.contains(field) ? "primary" : "key");
            row.put("options", options);
            row.put("selected", sel != null ? sel : "");
            rows.add(row);
        }
        boolean emptyCombination = rows.isEmpty() || !probeCombinationHasData(datasetId, workingSelected);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataset_id", datasetId);
        out.put("selected_dimensions", workingSelected);
        out.put("dimensions", rows);
        out.put("invalid_removed", dedupStrings(invalidRemoved));
        out.put("empty_combination", emptyCombination);
        out.put("availability_mode", emptyCombination ? "no_data" : "latest_only");
        out.put("method_used", invalidRemoved.isEmpty() && !emptyCombination ? "live_probe" : "live_probe_partial");
        out.put("complete", true);
        out.put("notice_cs", "Zobrazeny jsou hodnoty dimenzí ověřené live dotazem (lastTimePeriod=1).");
        return out;
    }

    private record ProbeFilterResult(List<Map<String, String>> options, List<String> removed) {}

    private ProbeFilterResult filterOptionsByLiveProbe(
            String datasetId,
            Map<String, String> selected,
            String targetDimension,
            List<Map<String, String>> options,
            int maxProbes) {
        if (options == null || options.isEmpty()) {
            return new ProbeFilterResult(List.of(), List.of());
        }
        int probeCount = Math.min(Math.max(maxProbes, 0), options.size());
        List<Map<String, String>> toProbe = options.subList(0, probeCount);
        List<Map<String, String>> passthrough = options.subList(probeCount, options.size());

        // Fan out the probe requests for this dimension concurrently — probeCombinationHasData
        // already swallows exceptions/timeouts internally and returns false, so the futures
        // always complete normally; wall time is bounded by the slowest single probe instead of
        // the sum of all of them.
        List<CompletableFuture<Boolean>> futures = new ArrayList<>(toProbe.size());
        for (Map<String, String> option : toProbe) {
            Map<String, String> trial = new LinkedHashMap<>(selected);
            trial.put(targetDimension, option.get("code"));
            futures.add(CompletableFuture.supplyAsync(() -> probeCombinationHasData(datasetId, trial), probeExecutor));
        }

        List<Map<String, String>> valid = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (int i = 0; i < toProbe.size(); i++) {
            boolean hasData;
            try {
                hasData = futures.get(i).join();
            } catch (Exception ex) {
                hasData = false;
            }
            if (hasData) {
                valid.add(toProbe.get(i));
            } else {
                removed.add(toProbe.get(i).get("code"));
            }
        }
        valid.addAll(passthrough);
        if (valid.isEmpty() && !options.isEmpty()) {
            valid.add(options.get(0));
        }
        return new ProbeFilterResult(valid, removed);
    }

    private Map<String, String> cascadeResolveSelection(
            String datasetId, Map<String, Map<String, Object>> dims, Map<String, String> seed) {
        List<String> order = orderedDimensionKeys(dims);
        Map<String, String> workingSelected = new LinkedHashMap<>(seed);
        for (String field : order) {
            if (SKIP_DIM_KEYS.contains(field.toLowerCase(Locale.ROOT))) {
                continue;
            }
            List<Map<String, String>> options = buildOptions(dims.get(field));
            if (options.isEmpty()) {
                continue;
            }
            if ("coicop".equalsIgnoreCase(field)) {
                options = orderGeoOptions(options, List.of("CP00", "CP01", "TOTAL", "ALL"));
            }
            ProbeFilterResult filtered = filterOptionsByLiveProbe(datasetId, workingSelected, field, options, 16);
            options = filtered.options();
            String sel = workingSelected.getOrDefault(field, options.isEmpty() ? "" : options.get(0).get("code"));
            if (!sel.isBlank()) {
                String current = sel;
                if (options.stream().noneMatch(o -> current.equals(o.get("code")))) {
                    sel = options.isEmpty() ? "" : options.get(0).get("code");
                }
            }
            if (!sel.isBlank()) {
                workingSelected.put(field, sel);
            }
        }
        return workingSelected;
    }

    private Map<String, String> buildDefaultSelection(Map<String, Map<String, Object>> dims, String preferredGeo) {
        Map<String, String> selection = new LinkedHashMap<>();
        List<String> geoPreferred = geoPreferenceList(preferredGeo);
        pickDefault(selection, dims, "geo", geoPreferred);
        pickDefault(selection, dims, "ref_area", geoPreferred);
        pickDefault(selection, dims, "country", geoPreferred);
        pickDefault(selection, dims, "freq", List.of("M", "Q", "A"));
        pickDefault(selection, dims, "unit", List.of("PC", "PCH", "I15", "I16", "RCH_A", "RCH_M", "EUR", "INDEX"));
        pickDefault(selection, dims, "coicop", List.of("CP00", "TOTAL", "ALL"));
        // Eurostat short-term business statistics family (sts_inpr_m/sts_intv_m/sts_inpp_m/...)
        // exposes indic_bt (which measure) and nace_r2 (which NACE activity) as separate dimensions
        // with hundreds of possible values each - left unset, the live-probe cascade below has to
        // narrow 2-3 deeply ambiguous dimensions from scratch, and each intermediate probe (e.g.
        // geo alone, or geo+one dimension) can itself request a cross-product large enough that
        // Eurostat's own API rejects it with HTTP 413 before a valid combination is ever found
        // (confirmed live for sts_inpr_m/geo=IT). Picking these two up front - and s_adj, whose
        // available values differ per dataset (sts_inpp_m has no SCA variant, only CA/NSA) - keeps
        // the very first probeCombinationHasData attempt below fully narrowed already.
        pickDefault(selection, dims, "indic_bt", List.of("PRD", "NETTUR", "PRC_PRR"));
        pickDefault(selection, dims, "nace_r2", List.of("B_C", "C", "B-D"));
        pickDefault(selection, dims, "s_adj", List.of("SCA", "CA", "NSA"));
        for (String key : orderedDimensionKeys(dims)) {
            if (selection.containsKey(key) || SKIP_DIM_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                continue;
            }
            List<Map<String, String>> options = buildOptions(dims.get(key));
            if (options.size() == 1) {
                selection.put(key, options.get(0).get("code"));
            }
        }
        return selection;
    }

    private Map<String, String> refineSelectionByProbe(
            String datasetId,
            Map<String, Map<String, Object>> dims,
            Map<String, String> selection,
            String preferredGeo) {
        Map<String, String> working = new LinkedHashMap<>(selection);
        List<String> geoPreferred = geoPreferenceList(preferredGeo);
        for (String geoKey : List.of("geo", "ref_area", "country")) {
            Map<String, Object> spec = dims.get(geoKey);
            if (spec == null) {
                continue;
            }
            List<Map<String, String>> options = buildOptions(spec);
            if (options.isEmpty()) {
                continue;
            }
            List<Map<String, String>> ordered = orderGeoOptions(options, geoPreferred);
            Map<String, String> base = new LinkedHashMap<>(working);
            base.remove(geoKey);
            for (Map<String, String> option : ordered) {
                Map<String, String> trial = new LinkedHashMap<>(base);
                trial.put(geoKey, option.get("code"));
                if (probeCombinationHasData(datasetId, trial)) {
                    working.put(geoKey, option.get("code"));
                    return working;
                }
            }
        }
        for (String field : orderedDimensionKeys(dims)) {
            if (SKIP_DIM_KEYS.contains(field.toLowerCase(Locale.ROOT))
                    || "geo".equals(field)
                    || "ref_area".equals(field)
                    || "country".equals(field)) {
                continue;
            }
            List<Map<String, String>> options = buildOptions(dims.get(field));
            if (options.isEmpty()) {
                continue;
            }
            if ("coicop".equalsIgnoreCase(field)) {
                options = orderGeoOptions(options, List.of("CP00", "TOTAL", "ALL", "CP01"));
            }
            ProbeFilterResult filtered = filterOptionsByLiveProbe(datasetId, working, field, options, 12);
            if (!filtered.options().isEmpty()) {
                working.put(field, filtered.options().get(0).get("code"));
                if (probeCombinationHasData(datasetId, working)) {
                    return working;
                }
            }
        }
        return working;
    }

    private static List<String> geoPreferenceList(String preferredGeo) {
        List<String> out = new ArrayList<>();
        if (preferredGeo != null && !preferredGeo.isBlank()) {
            out.add(preferredGeo.trim().toUpperCase(Locale.ROOT));
        }
        out.addAll(List.of("CZ", "SK", "DE", "PL", "HU", "EU27_2020", "EU28"));
        return out;
    }

    private static List<Map<String, String>> orderGeoOptions(
            List<Map<String, String>> options, List<String> preferred) {
        List<Map<String, String>> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String code : preferred) {
            for (Map<String, String> option : options) {
                if (code.equalsIgnoreCase(option.get("code")) && seen.add(option.get("code"))) {
                    out.add(option);
                }
            }
        }
        for (Map<String, String> option : options) {
            if (seen.add(option.get("code"))) {
                out.add(option);
            }
        }
        return out;
    }

    private boolean probeCombinationHasData(String datasetId, Map<String, String> selected) {
        if (selected == null || selected.isEmpty()) {
            return false;
        }
        boolean acquired = false;
        try {
            acquired = eurostatCallLimiter.tryAcquire(15, java.util.concurrent.TimeUnit.SECONDS);
            if (!acquired) {
                return false;
            }
            StringBuilder query = new StringBuilder("format=JSON&lang=EN&lastTimePeriod=1");
            for (Map.Entry<String, String> entry : selected.entrySet()) {
                if (entry.getKey().isBlank() || entry.getValue().isBlank()) {
                    continue;
                }
                if (SKIP_DIM_KEYS.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                query.append("&").append(urlEncode(entry.getKey())).append("=").append(urlEncode(entry.getValue()));
            }
            URI uri = URI.create(BASE + "/" + datasetId + "?" + query);
            HttpRequest request = HttpRequest.newBuilder().uri(uri).timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return false;
            }
            Map<String, Object> body = objectMapper.readValue(
                    response.body(), objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
            Object valueObj = body.get("value");
            if (valueObj instanceof Map<?, ?> values && !values.isEmpty()) {
                return true;
            }
            Object dimObj = body.get("dimension");
            return dimObj instanceof Map<?, ?> dims && !dims.isEmpty() && body.containsKey("size");
        } catch (Exception ex) {
            log.debug("Eurostat probe failed for {}: {}", datasetId, ex.getMessage());
            return false;
        } finally {
            if (acquired) {
                eurostatCallLimiter.release();
            }
        }
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static List<String> dedupStrings(List<String> values) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String value : values) {
            String text = value == null ? "" : value.strip();
            if (!text.isBlank() && seen.add(text)) {
                out.add(text);
            }
        }
        return out;
    }

    private Map<String, Object> availableOptions(String datasetId, Map<String, String> selected, String targetDimension) {
        Map<String, Object> meta = fetchMetadata(datasetId);
        if (meta == null) {
            return Map.of(
                    "dataset_id", datasetId,
                    "target_dimension", targetDimension,
                    "options", List.of(),
                    "complete", false);
        }
        Map<String, Map<String, Object>> dims = metadataDimensions(meta);
        Map<String, Object> spec = dims.get(targetDimension);
        List<Map<String, String>> options = buildOptions(spec);
        ProbeFilterResult filtered = filterOptionsByLiveProbe(datasetId, selected, targetDimension, options, 32);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataset_id", datasetId);
        out.put("target_dimension", targetDimension);
        out.put("selected_dimensions", selected);
        out.put("options", filtered.options());
        out.put("invalid_removed", filtered.removed());
        out.put("complete", true);
        out.put("method_used", "live_probe");
        return out;
    }

    private Map<String, Object> fetchMetadata(String datasetId) {
        CachedMeta cached = cache.get(datasetId);
        if (cached != null && cached.expiresAtMs > System.currentTimeMillis()) {
            return cached.body;
        }
        boolean acquired = false;
        try {
            acquired = eurostatCallLimiter.tryAcquire(15, java.util.concurrent.TimeUnit.SECONDS);
            if (!acquired) {
                return null;
            }
            URI uri = URI.create(BASE + "/" + datasetId + "?format=JSON&lang=EN&lastTimePeriod=1");
            HttpRequest request = HttpRequest.newBuilder().uri(uri).timeout(Duration.ofSeconds(15)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            Map<String, Object> body = objectMapper.readValue(response.body(), objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
            cache.put(datasetId, new CachedMeta(body, System.currentTimeMillis() + Duration.ofHours(1).toMillis()));
            return body;
        } catch (Exception ex) {
            log.debug("Eurostat metadata fetch failed for {}: {}", datasetId, ex.getMessage());
            return null;
        } finally {
            if (acquired) {
                eurostatCallLimiter.release();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> metadataDimensions(Map<String, Object> metadata) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        Object idsRaw = metadata.get("id");
        Object sizeRaw = metadata.get("size");
        Object dimRaw = metadata.get("dimension");
        if (!(idsRaw instanceof List<?> ids) || !(dimRaw instanceof Map<?, ?> dimensions)) {
            return out;
        }
        List<?> sizes = sizeRaw instanceof List<?> list ? list : List.of();
        for (int idx = 0; idx < ids.size(); idx++) {
            String dimKey = String.valueOf(ids.get(idx)).trim();
            if (dimKey.isBlank()) {
                continue;
            }
            Object dimMetaObj = dimensions.get(dimKey);
            if (!(dimMetaObj instanceof Map<?, ?> dimMetaMap)) {
                continue;
            }
            Map<String, Object> dimMeta = (Map<String, Object>) dimMetaMap;
            Object categoryObj = dimMeta.get("category");
            Map<String, Object> category = categoryObj instanceof Map<?, ?> catMap ? (Map<String, Object>) catMap : Map.of();
            List<String> values = new ArrayList<>();
            Object indexObj = category.get("index");
            if (indexObj instanceof Map<?, ?> indexMap) {
                indexMap.keySet().forEach(k -> values.add(String.valueOf(k)));
            } else if (indexObj instanceof List<?> indexList) {
                indexList.forEach(v -> values.add(String.valueOf(v)));
            }
            Map<String, String> labels = new LinkedHashMap<>();
            Object labelObj = category.get("label");
            if (labelObj instanceof Map<?, ?> labelMap) {
                for (String code : values) {
                    Object label = labelMap.get(code);
                    if (label != null) {
                        labels.put(code, String.valueOf(label));
                    }
                }
            }
            Object dimLabelObj = dimMeta.get("label");
            String dimLabel = dimLabelObj != null ? String.valueOf(dimLabelObj) : dimKey;
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("values", values);
            spec.put("value_labels", labels);
            spec.put("label", dimLabel);
            if (idx < sizes.size()) {
                spec.put("size", sizes.get(idx));
            }
            out.put(dimKey, spec);
        }
        return out;
    }

    private static List<String> orderedDimensionKeys(Map<String, Map<String, Object>> dims) {
        List<String> keys = new ArrayList<>(dims.keySet());
        keys.sort((a, b) -> {
            int ia = PRIMARY_ORDER.indexOf(a);
            int ib = PRIMARY_ORDER.indexOf(b);
            if (ia >= 0 || ib >= 0) {
                return Integer.compare(ia >= 0 ? ia : 999, ib >= 0 ? ib : 999);
            }
            return a.compareToIgnoreCase(b);
        });
        return keys;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> buildOptions(Map<String, Object> spec) {
        if (spec == null) {
            return List.of();
        }
        Object valuesRaw = spec.get("values");
        Map<String, String> labels = spec.get("value_labels") instanceof Map<?, ?> map ? (Map<String, String>) map : Map.of();
        if (!(valuesRaw instanceof List<?> values)) {
            return List.of();
        }
        List<Map<String, String>> out = new ArrayList<>();
        for (Object value : values) {
            String code = String.valueOf(value).trim();
            if (code.isBlank()) {
                continue;
            }
            out.add(Map.of("code", code, "label", labels.getOrDefault(code, code)));
            if (out.size() >= 80) {
                break;
            }
        }
        return out;
    }

    private static void pickDefault(
            Map<String, String> selection, Map<String, Map<String, Object>> dims, String key, List<String> preferred) {
        Map<String, Object> spec = dims.get(key);
        if (spec == null) {
            return;
        }
        List<Map<String, String>> options = buildOptions(spec);
        for (String code : preferred) {
            if (options.stream().anyMatch(o -> code.equalsIgnoreCase(o.get("code")))) {
                selection.put(key, code);
                return;
            }
        }
        if (!options.isEmpty()) {
            selection.put(key, options.get(0).get("code"));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> normalizeSelected(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey()).trim();
            if (key.isBlank() || SKIP_DIM_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                continue;
            }
            String value = String.valueOf(entry.getValue() != null ? entry.getValue() : "").trim();
            if (!value.isBlank()) {
                out.put(key, value);
            }
        }
        return out;
    }

    private static String cleanDatasetId(String datasetId) {
        String sid = datasetId != null ? datasetId.trim().toLowerCase(Locale.ROOT) : "";
        if (sid.isBlank() || sid.length() > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Neplatný dataset_id.");
        }
        return sid;
    }

    private record CachedMeta(Map<String, Object> body, long expiresAtMs) {}
}
