package cz.bankintel.controller.catalog;

import cz.bankintel.search.CatalogClassicSearchService;
import cz.bankintel.search.CatalogDeepSearchService;
import cz.bankintel.search.CatalogFollowupService;
import cz.bankintel.search.CatalogMultiSearchService;
import cz.bankintel.search.CatalogPreviewService;
import cz.bankintel.search.CatalogSearchStreamService;
import cz.bankintel.search.CatalogSourceRouteService;
import cz.bankintel.search.CatalogStatusService;
import cz.bankintel.search.CatalogSuggestService;
import cz.bankintel.search.CatalogWarmupService;
import cz.bankintel.search.ChartDataQualityService;
import cz.bankintel.search.CatalogDownloadService;
import cz.bankintel.search.CatalogRelatedSeriesService;
import cz.bankintel.search.CatalogSeriesExplainService;
import cz.bankintel.search.forecast.CatalogForecastService;
import cz.bankintel.search.analytics.CatalogAnalyticsService;
import cz.bankintel.search.v2.evaluation.SearchV2Evaluator;
import cz.bankintel.search.v2.observability.SearchV2ShadowStore;
import cz.bankintel.search.v2.observability.SearchV2TraceStore;
import cz.bankintel.search.v2.orchestration.SearchV2FeatureFlags;
import cz.bankintel.search.v2.orchestration.SearchV2Service;
import cz.bankintel.search.v2.sidecar.SearchCatalogSidecarIndex;
import cz.bankintel.search.v2.vector.SearchVectorIndexBuilder;
import cz.bankintel.security.AdminAccess;
import cz.bankintel.security.CurrentUser;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Katalogové vyhledávání a náhledy — {@code /api/catalog/*}.
 *
 * <p>Nejdůležitější pro UI: {@code POST /preview} (graf), {@code POST /search}, {@code GET /suggest}.
 */
@RestController
@RequestMapping("/api/catalog")
@RequiredArgsConstructor
public class CatalogController {

    private final CatalogSuggestService catalogSuggestService;
    private final CatalogClassicSearchService catalogClassicSearchService;
    private final CatalogPreviewService catalogPreviewService;
    private final CatalogStatusService catalogStatusService;
    private final CatalogDeepSearchService catalogDeepSearchService;
    private final CatalogMultiSearchService catalogMultiSearchService;
    private final CatalogSourceRouteService catalogSourceRouteService;
    private final CatalogSearchStreamService catalogSearchStreamService;
    private final CatalogFollowupService catalogFollowupService;
    private final CatalogWarmupService catalogWarmupService;
    private final ChartDataQualityService chartDataQualityService;
    private final CatalogDownloadService catalogDownloadService;
    private final CatalogSeriesExplainService catalogSeriesExplainService;
    private final CatalogRelatedSeriesService catalogRelatedSeriesService;
    private final CatalogForecastService catalogForecastService;
    private final CatalogAnalyticsService catalogAnalyticsService;
    private final SearchV2FeatureFlags searchV2FeatureFlags;
    private final SearchV2Service searchV2Service;
    private final SearchV2Evaluator searchV2Evaluator;
    private final SearchV2TraceStore searchV2TraceStore;
    private final SearchV2ShadowStore searchV2ShadowStore;
    private final SearchCatalogSidecarIndex searchCatalogSidecarIndex;
    private final SearchVectorIndexBuilder searchVectorIndexBuilder;
    private final CurrentUser currentUser;
    private final AdminAccess adminAccess;

    @GetMapping("/suggest")
    public Map<String, Object> suggest(
            @RequestParam(name = "q", defaultValue = "") String q,
            @RequestParam(name = "limit", defaultValue = "10") int limit,
            @RequestParam(name = "scope", required = false) String scope) {
        return catalogSuggestService.suggest(q, limit, scope);
    }

    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody Map<String, Object> body) {
        return catalogClassicSearchService.search(body);
    }

    @PostMapping("/preview")
    public Map<String, Object> preview(@RequestBody Map<String, Object> body) {
        return catalogPreviewService.preview(body);
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return catalogStatusService.status();
    }

    @GetMapping("/warmup")
    public Map<String, Object> warmup() {
        return catalogWarmupService.triggerWarmup();
    }

    @GetMapping("/warmup/status")
    public Map<String, Object> warmupStatus() {
        return catalogWarmupService.status();
    }

    @PostMapping("/chart-data-quality")
    public Map<String, Object> chartDataQuality(@RequestBody(required = false) Map<String, Object> body) {
        return chartDataQualityService.assess(body != null ? body : Map.of());
    }

    @PostMapping("/explain-series")
    public Map<String, Object> explainSeries(@RequestBody(required = false) Map<String, Object> body) {
        return catalogSeriesExplainService.explainSeries(body != null ? body : Map.of());
    }

    @PostMapping("/explain-series/ask")
    public Map<String, Object> explainSeriesAsk(@RequestBody(required = false) Map<String, Object> body) {
        return catalogSeriesExplainService.askFollowup(body != null ? body : Map.of());
    }

    @PostMapping("/related-series")
    public Map<String, Object> relatedSeries(@RequestBody(required = false) Map<String, Object> body) {
        return catalogRelatedSeriesService.findRelated(body != null ? body : Map.of());
    }

    @PostMapping("/forecast")
    public Map<String, Object> forecast(@RequestBody(required = false) Map<String, Object> body) {
        return catalogForecastService.forecast(body != null ? body : Map.of());
    }

    @PostMapping("/analytics")
    public Map<String, Object> analytics(@RequestBody(required = false) Map<String, Object> body) {
        return catalogAnalyticsService.analyze(body != null ? body : Map.of());
    }

    @PostMapping("/download")
    public ResponseEntity<byte[]> download(@RequestBody(required = false) Map<String, Object> body) {
        return catalogDownloadService.download(body != null ? body : Map.of(), currentUser.optionalUserEntity());
    }

    @PostMapping("/deep-search")
    public Map<String, Object> deepSearch(@RequestBody Map<String, Object> body) {
        Map<String, Object> payload = body != null ? body : Map.of();
        Map<String, Object> result = searchV2FeatureFlags.useV2(payload)
                ? searchV2Service.search(payload)
                : catalogDeepSearchService.deepSearch(payload);
        if (!searchV2FeatureFlags.useV2(payload) && searchV2FeatureFlags.shadowMode()) {
            Map<String, Object> shadow = searchV2Service.search(shadowPayload(payload));
            result.put("_search_v2_shadow", searchV2ShadowStore.save(firstNonBlank(payload, "q", "query"), result, shadow));
        }
        String query = firstNonBlank(payload, "q", "query");
        result.put(
                "conversation",
                catalogFollowupService.bootstrapConversation(result, currentUser.optionalUserEntity(), payload, query));
        return result;
    }

    @PostMapping("/search-v2")
    public Map<String, Object> searchV2(@RequestBody(required = false) Map<String, Object> body) {
        return searchV2Service.search(body != null ? body : Map.of());
    }

    @PostMapping("/search-v2/evaluate")
    public Map<String, Object> searchV2Evaluate(@RequestBody(required = false) Map<String, Object> body) {
        return searchV2Evaluator.evaluate(body != null ? body : Map.of());
    }

    /**
     * Admin-only: a full sidecar rebuild walks the whole catalog and is one of the most expensive
     * operations the backend can run. Neither this controller nor {@code SearchCatalogSidecarIndex}
     * checked anything, and no UI calls it — so it was reachable by anyone able to reach the server.
     */
    @PostMapping("/search-v2/sidecar/rebuild")
    public Map<String, Object> searchV2SidecarRebuild(@RequestBody(required = false) Map<String, Object> body) {
        adminAccess.requireAdmin();
        Map<String, Object> payload = body != null ? body : Map.of();
        return searchCatalogSidecarIndex.rebuild(readSources(payload.get("sources")));
    }

    @GetMapping("/search-v2/sidecar/coverage")
    public Map<String, Object> searchV2SidecarCoverage() {
        return searchCatalogSidecarIndex.coverage();
    }

    /** Admin-only for the same reason as the rebuild above. */
    @PostMapping("/search-v2/sidecar/optimize")
    public Map<String, Object> searchV2SidecarOptimize() {
        adminAccess.requireAdmin();
        return searchCatalogSidecarIndex.optimize();
    }

    @GetMapping("/search-v2/vector/status")
    public Map<String, Object> searchV2VectorStatus() {
        return searchVectorIndexBuilder.status();
    }

    /** Admin-only: re-embeds the whole catalog, the most expensive job in the search stack. */
    @PostMapping("/search-v2/vector/rebuild")
    public Map<String, Object> searchV2VectorRebuild() {
        adminAccess.requireAdmin();
        return searchVectorIndexBuilder.rebuild();
    }

    @GetMapping("/search-v2/trace/{traceId}")
    public Map<String, Object> searchV2Trace(@PathVariable String traceId) {
        return searchV2TraceStore.get(traceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Trace nenalezen."));
    }

    @GetMapping("/search-v2/shadow")
    public Map<String, Object> searchV2Shadow() {
        return Map.of("items", searchV2ShadowStore.recent());
    }

    @PostMapping("/source-route")
    public Map<String, Object> sourceRoute(@RequestBody Map<String, Object> body) {
        return catalogSourceRouteService.routeSources(body != null ? body : Map.of());
    }

    @PostMapping("/deep-search/source-route")
    public Map<String, Object> deepSearchSourceRoute(@RequestBody Map<String, Object> body) {
        return catalogSourceRouteService.routeSources(body != null ? body : Map.of());
    }

    @PostMapping("/search/multi")
    public Map<String, Object> searchMulti(@RequestBody Map<String, Object> body) {
        return catalogMultiSearchService.multiSearch(body != null ? body : Map.of());
    }

    @GetMapping(value = "/search/multi/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter searchMultiStream(
            @RequestParam(name = "q", defaultValue = "") String q,
            @RequestParam(required = false) String sources,
            @RequestParam(required = false) String catalog,
            @RequestParam(required = false) String limit) {
        String query = q != null ? q.trim() : "";
        if (query.length() < 2) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Dotaz je příliš krátký.");
        }
        List<String> srcs = parseCsvSources(sources, catalog);
        if (srcs.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Vyberte alespoň jeden katalog.");
        }
        Integer lim = parseOptionalInt(limit);
        return catalogSearchStreamService.streamMultiSearch(query, srcs, lim);
    }

    @GetMapping(value = "/deep-search/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter deepSearchStream(
            @RequestParam(name = "q", defaultValue = "") String q,
            @RequestParam(required = false) String sources,
            @RequestParam(name = "use_ai", required = false) String useAi) {
        String query = q != null ? q.trim() : "";
        if (query.length() < 2) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Dotaz je příliš krátký.");
        }
        List<String> srcs = parseCsvSources(sources, null);
        Boolean ai = parseUseAi(useAi);
        return catalogSearchStreamService.streamDeepSearch(query, srcs, ai);
    }

    @PostMapping("/deep-search/followup")
    public Map<String, Object> deepSearchFollowup(@RequestBody Map<String, Object> body) {
        return catalogFollowupService.followup(currentUser.optionalUserEntity(), body != null ? body : Map.of());
    }

    @PostMapping("/deep-search/results-chat")
    public Map<String, Object> deepSearchResultsChat(@RequestBody Map<String, Object> body) {
        return catalogFollowupService.resultsChat(currentUser.optionalUserEntity(), body != null ? body : Map.of());
    }

    @PostMapping("/deep-search/results-intent")
    public Map<String, Object> deepSearchResultsIntent(@RequestBody Map<String, Object> body) {
        return catalogFollowupService.resultsIntent(body != null ? body : Map.of());
    }

    private static List<String> parseCsvSources(String sources, String catalog) {
        List<String> out = new java.util.ArrayList<>();
        for (String chunk : java.util.Arrays.asList(sources, catalog)) {
            if (chunk == null || chunk.isBlank()) {
                continue;
            }
            Arrays.stream(chunk.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .map(String::toLowerCase)
                    .forEach(s -> {
                        if (!out.contains(s)) {
                            out.add(s);
                        }
                    });
        }
        return out;
    }

    private static List<String> readSources(Object raw) {
        if (raw == null) {
            return List.of();
        }
        List<String> out = new java.util.ArrayList<>();
        if (raw instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                addSource(out, String.valueOf(item));
            }
        } else {
            for (String part : String.valueOf(raw).split(",")) {
                addSource(out, part);
            }
        }
        return out;
    }

    private static void addSource(List<String> out, String raw) {
        String source = raw == null ? "" : raw.trim().toLowerCase();
        if (!source.isBlank() && !out.contains(source)) {
            out.add(source);
        }
    }

    private static Integer parseOptionalInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Boolean parseUseAi(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return !List.of("0", "false", "no", "off").contains(raw.trim().toLowerCase());
    }

    private static Map<String, Object> shadowPayload(Map<String, Object> payload) {
        Map<String, Object> out = new java.util.LinkedHashMap<>(payload);
        out.put("search_engine_version", "v2");
        out.put("debug", true);
        return out;
    }

    private static String firstNonBlank(Map<String, Object> payload, String... keys) {
        for (String key : keys) {
            Object value = payload.get(key);
            if (value != null) {
                String text = String.valueOf(value).trim();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }
}
