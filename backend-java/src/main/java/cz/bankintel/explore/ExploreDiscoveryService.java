package cz.bankintel.explore;

import cz.bankintel.search.CatalogDeepSearchService;
import cz.bankintel.search.CatalogSearchVariantDedup;
import cz.bankintel.search.CatalogSourceRegistry;
import cz.bankintel.search.model.CatalogMapSupport;
import cz.bankintel.search.model.GeoIntentSnapshot;
import cz.bankintel.search.v2.normalization.SearchResultCanonicalMetadataService;
import cz.bankintel.search.v2.orchestration.SearchV2FeatureFlags;
import cz.bankintel.search.v2.orchestration.SearchV2Service;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ExploreDiscoveryService {

    private static final List<String> DEFAULT_SOURCES = CatalogSourceRegistry.EXPLORE_DISCOVERY_DEFAULT_SOURCES;

    // Each task is I/O-bound (a full multi-source catalog fan-out, same as any other discover()
    // call) - virtual threads let a 2-4 segment question run at roughly the cost of one segment,
    // matching the concurrency idiom already used elsewhere in Explore (ExploreStreamService).
    private static final ExecutorService MULTI_SECTOR_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final CatalogDeepSearchService catalogDeepSearchService;
    private final ExploreDiscoveryCache discoveryCache;
    private final SearchResultCanonicalMetadataService canonicalMetadataService;
    private final SearchV2FeatureFlags searchV2FeatureFlags;
    private final SearchV2Service searchV2Service;

    /**
     * Manager Explorer hledal pořád přes V1 engine, i když je zapnuté {@code SEARCH_ENGINE_VERSION=v2}.
     *
     * <p>{@code CatalogController#deepSearch} volbu engine řeší přes {@link SearchV2FeatureFlags},
     * ale discovery Exploreru volalo {@link CatalogDeepSearchService#deepSearch} natvrdo — takže
     * uživatel viděl v katalogovém hledání jiné (a výrazně lepší) výsledky než v Exploreru pro
     * úplně stejný dotaz. Naměřeno na „Ziskovost bank a objem úvěrů v ČR": stejný payload dal přes
     * V2 osm ověřených řad (Bank's ROE/ROA for Czech Republic, ARAD rentabilita aktiv, čisté
     * úrokové výnosy, úvěry), zatímco přes V1 skončil Explorer u dvou řad o objemu osobní
     * a nákladní dopravy k HDP. Rozhoduje se tu stejně jako v controlleru, aby obě cesty
     * nemohly znovu utéct od sebe.
     */
    private Map<String, Object> runDeepSearch(Map<String, Object> payload) {
        return searchV2FeatureFlags.useV2(payload)
                ? searchV2Service.search(withV2DiscoveryTuning(payload))
                : catalogDeepSearchService.deepSearch(payload);
    }

    /**
     * Doplní klíče, které dávají smysl jen pro Search V2. Záměrně se nepřidávají do
     * {@link #buildPayload}: V1 si zdroje zužuje sám ({@code narrowSourcesForGeo}) a předat mu
     * celý výchozí seznam by se tvářilo jako uživatelský filtr - viz {@code
     * ExploreDiscoveryServiceTest#discoverRequestsNoAiStoryFromDeepSearch}.
     *
     * <p><b>limit + preview_mode:</b> V2 ověřuje živým náhledem nejvýš {@code MAX_PREVIEW_VERIFY = 8}
     * kandidátů a ve výchozím režimu {@code full} zbytek ranku zahodí, takže {@code verified} byl
     * zároveň celý výsledek. V {@code top_preview} zůstane ověřování stejné, ale zbytek se vrátí
     * jako {@code possible} - naměřeno results 6 -> 24 (verified 6, possible 18), 11,4 s -> 8,0 s.
     *
     * <p><b>sources:</b> router V2 si pro tenhle dotaz sám zúžil výběr na čtyři zdroje
     * ({@code ecb2, bis, imf, eurostat}), takže ARAD a ČSÚ - přesně ty s českými bankovními
     * řadami - se vůbec nedotazovaly. Naměřeno na "Ziskovost bank a objem úvěrů v ČR":
     * bez toho {@code lanes = ecb2:231, bis:0, imf:0, eurostat:0} (results 9),
     * s tím {@code lanes = ecb2:227, arad:5, data360:24, …} (results 18).
     */
    private static Map<String, Object> withV2DiscoveryTuning(Map<String, Object> payload) {
        Map<String, Object> tuned = new LinkedHashMap<>(payload);
        boolean broader = Boolean.TRUE.equals(payload.get("broader_search"))
                || CatalogMapSupport.toInt(payload.get("limit_per_source"), 6) > 6;
        tuned.putIfAbsent("limit", broader ? 30 : 24);
        tuned.putIfAbsent("preview_mode", "top_preview");
        tuned.putIfAbsent("preview_top_n", 8);
        tuned.putIfAbsent(
                "sources",
                CatalogSourceRegistry.EXPLORE_DISCOVERY_DEFAULT_SOURCES.stream()
                        .map(CatalogSourceRegistry::normalizeSearchSource)
                        .distinct()
                        .toList());
        return tuned;
    }

    public IndicatorBundle discover(String question, String sector, boolean broaderSearch) {
        return discover(question, sector, broaderSearch, List.of());
    }

    /**
     * @param resolvedCountryCodes the caller's already-resolved target geo (e.g. a continent's
     *     member countries from {@link ExploreGeoResolver}) — used as a fallback when the query
     *     text itself names no specific country (e.g. "Evropa"), so generic macro-scaffold rows
     *     pinned to a country outside that set can be recognized as geo-irrelevant noise.
     */
    public IndicatorBundle discover(
            String question, String sector, boolean broaderSearch, List<String> resolvedCountryCodes) {
        String query = question != null && !question.isBlank() ? question.trim() : sector;
        if (query == null || query.isBlank()) {
            return IndicatorBundle.empty();
        }
        long t0 = System.currentTimeMillis();
        String cacheKey = discoveryCache.buildKey(question, sector, broaderSearch);
        Optional<ExploreDiscoveryCache.CachedEntry> cached = discoveryCache.get(cacheKey);
        if (cached.isPresent()) {
            ExploreDiscoveryCache.CachedEntry entry = cached.get();
            return new IndicatorBundle(
                    entry.sectorIndicators(), entry.macroIndicators(), entry.totalCandidates(), true,
                    System.currentTimeMillis() - t0, entry.computeTimeMs());
        }
        Map<String, Object> result = runDeepSearch(buildPayload(query, broaderSearch));
        IndicatorBundle bundle = buildIndicatorBundle(result, sector, query, resolvedCountryCodes);
        long computeTimeMs = System.currentTimeMillis() - t0;
        discoveryCache.put(
                cacheKey,
                bundle.sectorIndicators(),
                bundle.macroIndicators(),
                bundle.totalCandidates(),
                computeTimeMs);
        return bundle.withTiming(false, computeTimeMs, computeTimeMs);
    }

    /**
     * Cold-path benchmark entry point. It skips cache reads and writes, so profiling neither
     * evicts nor contaminates normal discovery entries. Callers must gate it behind the dedicated
     * {@code cold-path-profile} Spring profile.
     */
    public IndicatorBundle discoverUncachedForProfile(String question, String sector, boolean broaderSearch) {
        return discoverUncachedForProfile(question, sector, broaderSearch, List.of());
    }

    public IndicatorBundle discoverUncachedForProfile(
            String question, String sector, boolean broaderSearch, List<String> resolvedCountryCodes) {
        String query = question != null && !question.isBlank() ? question.trim() : sector;
        if (query == null || query.isBlank()) {
            return IndicatorBundle.empty();
        }
        long t0 = System.currentTimeMillis();
        Map<String, Object> result = runDeepSearch(buildPayload(query, broaderSearch));
        IndicatorBundle bundle = buildIndicatorBundle(result, sector, query, resolvedCountryCodes);
        long computeTimeMs = System.currentTimeMillis() - t0;
        return bundle.withTiming(false, computeTimeMs, computeTimeMs);
    }

    /**
     * A question naming 2+ distinct segments ("stavebnictví nebo autovýroba") must not run as ONE
     * combined free-text search: {@link #discover} caps each call's merged candidate pool at 12
     * ({@link #buildIndicatorBundle}), so whichever segment's Czech label happens to match catalog
     * titles more literally wins that shared budget and the other segment gets zero rows - confirmed
     * live ("stavebnictví" returned 12 rows, "autovýroba" returned 0 for the same combined query).
     * Running one independent {@link #discover} call per segment gives each its own 12-row budget;
     * segments run concurrently (virtual threads - each call is I/O-bound, waiting on the same
     * multi-source catalog fan-out {@link #discover} already does internally) so answering N
     * segments costs roughly the same wall time as answering 1, not N times as long.
     *
     * @param sectors 2+ distinct segment labels (see {@code ExploreSectorService.sectorSegmentsFrom})
     * @param uncached mirrors {@link #discoverUncachedForProfile} - use only under the {@code
     *     cold-path-profile} benchmarking profile, never in normal request handling
     */
    public IndicatorBundle discoverMultiSector(
            List<String> sectors, boolean broaderSearch, List<String> resolvedCountryCodes, boolean uncached) {
        if (sectors == null || sectors.isEmpty()) {
            return IndicatorBundle.empty();
        }
        long t0 = System.currentTimeMillis();
        List<CompletableFuture<IndicatorBundle>> futures = new ArrayList<>();
        for (String segment : sectors) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> uncached
                            ? discoverUncachedForProfile(segment, segment, broaderSearch, resolvedCountryCodes)
                            : discover(segment, segment, broaderSearch, resolvedCountryCodes),
                    MULTI_SECTOR_EXECUTOR));
        }
        List<IndicatorBundle> bundles = futures.stream().map(CompletableFuture::join).toList();
        return mergeSegmentBundles(bundles, sectors, System.currentTimeMillis() - t0);
    }

    public IndicatorBundle discoverMultiSector(
            List<String> sectors, boolean broaderSearch, List<String> resolvedCountryCodes) {
        return discoverMultiSector(sectors, broaderSearch, resolvedCountryCodes, false);
    }

    /**
     * Concatenates every segment's rows, deduping by {@code (source, dataset_id)} - each segment's
     * own {@link #discover} call independently injects the SAME macro scaffold (GDP/inflation/FX),
     * so without this a 2-segment question would show every macro row twice.
     */
    private static IndicatorBundle mergeSegmentBundles(
            List<IndicatorBundle> bundles, List<String> sectors, long wallMs) {
        List<Map<String, Object>> sectorRows = new ArrayList<>();
        List<Map<String, Object>> macroRows = new ArrayList<>();
        Set<String> seenSector = new LinkedHashSet<>();
        Set<String> seenMacro = new LinkedHashSet<>();
        Set<String> plannedSources = new LinkedHashSet<>();
        int totalCandidates = 0;
        boolean allCacheHit = true;
        long cachedComputeSum = 0L;
        for (IndicatorBundle bundle : bundles) {
            totalCandidates += bundle.totalCandidates();
            allCacheHit = allCacheHit && bundle.cacheHit();
            cachedComputeSum += bundle.cachedComputeTimeMs();
            for (Map<String, Object> row : bundle.sectorIndicators()) {
                if (seenSector.add(indicatorDedupeKey(row))) {
                    sectorRows.add(row);
                }
            }
            for (Map<String, Object> row : bundle.macroIndicators()) {
                if (seenMacro.add(indicatorDedupeKey(row))) {
                    macroRows.add(row);
                }
            }
            // ExploreStreamService reads this to tell "genuinely not dispatched" sources apart from
            // "timed out" ones - losing it here would make every source look "planned" and mislabel
            // legitimately skipped ones (e.g. CZ-only sources for a foreign geo) as timeouts.
            Object planned = bundle.discoveryProfile().get("planned_sources");
            if (planned instanceof List<?> list) {
                for (Object item : list) {
                    plannedSources.add(String.valueOf(item));
                }
            }
        }
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("multi_sector", true);
        profile.put("sector_segments", sectors);
        if (!plannedSources.isEmpty()) {
            profile.put("planned_sources", List.copyOf(plannedSources));
        }
        return new IndicatorBundle(sectorRows, macroRows, totalCandidates, allCacheHit, wallMs, cachedComputeSum, profile);
    }

    private static String indicatorDedupeKey(Map<String, Object> row) {
        String source = String.valueOf(row.getOrDefault("source", "")).toLowerCase(Locale.ROOT);
        String datasetId = String.valueOf(row.getOrDefault("dataset_id", "")).trim();
        if (datasetId.isBlank() || "null".equalsIgnoreCase(datasetId)) {
            datasetId = String.valueOf(row.getOrDefault("title", ""));
        }
        return source + "|" + datasetId.toLowerCase(Locale.ROOT);
    }

    /**
     * Same underlying deep search as {@link #discover}, but streams per-source lane progress via
     * {@code consumer} as it runs and returns the resulting indicators - so a caller that needs
     * live progress (the SSE stream) can use this as its ONLY discovery pass instead of calling
     * {@link #discover} again afterwards for the real result. {@code CatalogDeepSearchService
     * .deepSearch(request)} is in fact implemented as {@code deepSearchWithLanes(request, null)},
     * so the two previously ran the exact same computation twice per stream request.
     *
     * <p>On a cache hit, real per-source lane events are still emitted (grouping the CACHED
     * indicator rows by their own "source" field over the same source list the live path would
     * have used) so the SSE progress UI (see ExploreStreamService) shows an honest, if
     * near-instant, per-source breakdown instead of nothing.
     */
    public IndicatorBundle discoverWithLanes(String question, String sector, boolean broaderSearch, LaneConsumer consumer) {
        return discoverWithLanes(question, sector, broaderSearch, List.of(), consumer);
    }

    public IndicatorBundle discoverWithLanes(
            String question,
            String sector,
            boolean broaderSearch,
            List<String> resolvedCountryCodes,
            LaneConsumer consumer) {
        String query = question != null && !question.isBlank() ? question.trim() : sector;
        if (query == null || query.isBlank()) {
            return IndicatorBundle.empty();
        }
        long t0 = System.currentTimeMillis();
        String cacheKey = discoveryCache.buildKey(question, sector, broaderSearch);
        Optional<ExploreDiscoveryCache.CachedEntry> cached = discoveryCache.get(cacheKey);
        if (cached.isPresent()) {
            ExploreDiscoveryCache.CachedEntry entry = cached.get();
            emitCachedLaneEvents(entry, consumer);
            return new IndicatorBundle(
                    entry.sectorIndicators(), entry.macroIndicators(), entry.totalCandidates(), true,
                    System.currentTimeMillis() - t0, entry.computeTimeMs());
        }
        Map<String, Integer> sourceCandidateCounts = new LinkedHashMap<>();
        Map<String, Object> payload = buildPayload(query, broaderSearch);
        Map<String, Object> result;
        if (searchV2FeatureFlags.useV2(payload)) {
            // V2 nemá per-lane callback (viz runDeepSearch). Postup zdrojů proto odvodíme
            // z hotového výsledku — stejný tvar události, jaký už umí emitCachedLaneEvents.
            // UI tím ztrácí průběžné odškrtávání zdrojů, ale dostane správná data; lane
            // události stejně nikdy nenesly výsledky, jen postup.
            result = searchV2Service.search(withV2DiscoveryTuning(payload));
            sourceCandidateCounts.putAll(candidateCountsBySource(resultRows(result)));
            sourceCandidateCounts.forEach((source, count) -> consumer.onLane(
                    source, Map.of("source", source, "count", count, "phase", "catalog_index")));
        } else {
            result = catalogDeepSearchService.deepSearchWithLanes(payload, lane -> {
                String source = CatalogSourceRegistry.normalizeSearchSource(String.valueOf(lane.get("source")));
                String phase = String.valueOf(lane.getOrDefault("phase", ""));
                if ("catalog_index".equals(phase) || "catalog_sidecar_timeout_fallback".equals(phase)) {
                    sourceCandidateCounts.put(source, ((Number) lane.getOrDefault("count", 0)).intValue());
                }
                consumer.onLane(source, lane);
            });
        }
        IndicatorBundle bundle = buildIndicatorBundle(result, sector, query, resolvedCountryCodes);
        long computeTimeMs = System.currentTimeMillis() - t0;
        discoveryCache.put(
                cacheKey,
                bundle.sectorIndicators(),
                bundle.macroIndicators(),
                bundle.totalCandidates(),
                computeTimeMs,
                sourceCandidateCounts);
        return bundle.withTiming(false, computeTimeMs, computeTimeMs);
    }

    /**
     * {@link #discoverWithLanes} equivalent of {@link #discoverMultiSector} - the SSE stream
     * (ExploreStreamService) needs live per-source progress even for a multi-segment question.
     * Each segment runs its own full {@link #discoverWithLanes} call (own lane events for every
     * source), but the UI shows ONE checkbox per source, not one per (source, segment) pair - so
     * a source's completion is forwarded as soon as ANY segment reports it, carrying the running
     * combined candidate count across whichever segments have reported so far.
     *
     * <p>Tried waiting for EVERY segment to report a source before forwarding once - wrong: a
     * source that legitimately succeeds for one segment but times out for another (common; each
     * segment's own search has its own wall-budget) would then NEVER cross the "all segments
     * reported" threshold, so the caller's own finalization logic marks it a false timeout even
     * though it contributed real rows to the merged result - confirmed live (8 of 12 sources
     * showed "timeout" despite eurostat/csu data plainly present in the final indicators).
     * Forwarding on every report means the caller may see the same source "finish" more than
     * once with a growing count - already-tolerated (ExploreStreamService just overwrites its
     * per-source status map on each event) and strictly safer than silently losing the event.
     */
    public IndicatorBundle discoverWithLanesMultiSector(
            List<String> sectors, boolean broaderSearch, List<String> resolvedCountryCodes, LaneConsumer consumer) {
        if (sectors == null || sectors.isEmpty()) {
            return IndicatorBundle.empty();
        }
        long t0 = System.currentTimeMillis();
        Map<String, Integer> combinedCounts = new ConcurrentHashMap<>();
        List<CompletableFuture<IndicatorBundle>> futures = new ArrayList<>();
        for (String segment : sectors) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> discoverWithLanes(segment, segment, broaderSearch, resolvedCountryCodes, (source, lane) -> {
                        String phase = String.valueOf(lane.getOrDefault("phase", ""));
                        if (!"catalog_index".equals(phase) && !"catalog_sidecar_timeout_fallback".equals(phase)) {
                            consumer.onLane(source, lane);
                            return;
                        }
                        int count = ((Number) lane.getOrDefault("count", 0)).intValue();
                        int combined = combinedCounts.merge(source, count, Integer::sum);
                        Map<String, Object> merged = new LinkedHashMap<>(lane);
                        merged.put("count", combined);
                        consumer.onLane(source, merged);
                    }),
                    MULTI_SECTOR_EXECUTOR));
        }
        List<IndicatorBundle> bundles = futures.stream().map(CompletableFuture::join).toList();
        return mergeSegmentBundles(bundles, sectors, System.currentTimeMillis() - t0);
    }

    private void emitCachedLaneEvents(ExploreDiscoveryCache.CachedEntry entry, LaneConsumer consumer) {
        if (!entry.sourceCandidateCounts().isEmpty()) {
            entry.sourceCandidateCounts().forEach((source, count) -> consumer.onLane(
                    source,
                    Map.of("source", source, "count", count, "phase", "catalog_index")));
            return;
        }

        // Compatibility for entries created by callers that do not stream lane metadata.
        List<Map<String, Object>> all = new ArrayList<>(entry.sectorIndicators());
        all.addAll(entry.macroIndicators());
        for (Map.Entry<String, Integer> sourceCount : candidateCountsBySource(all).entrySet()) {
            consumer.onLane(
                    sourceCount.getKey(),
                    Map.of("source", sourceCount.getKey(), "count", sourceCount.getValue(), "phase", "catalog_index"));
        }
    }

    /** Rows of a deep-search result, whichever engine produced it. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> resultRows(Map<String, Object> result) {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.addAll((List<Map<String, Object>>) result.getOrDefault("verified", List.of()));
        rows.addAll((List<Map<String, Object>>) result.getOrDefault("possible", List.of()));
        return rows;
    }

    /**
     * Per-source hit counts, seeded with 0 for every source Explorer asks about — a source with no
     * event at all is reported as a timeout by {@code ExploreStreamService}, so "searched, found
     * nothing" must still produce an event.
     */
    private static Map<String, Integer> candidateCountsBySource(List<Map<String, Object>> rows) {
        Map<String, Integer> countsBySource = new LinkedHashMap<>();
        for (String source : DEFAULT_SOURCES.stream().map(CatalogSourceRegistry::normalizeSearchSource).distinct().toList()) {
            countsBySource.put(source, 0);
        }
        for (Map<String, Object> row : rows) {
            String source = CatalogSourceRegistry.normalizeSearchSource(
                    String.valueOf(row.getOrDefault("source", row.get("source_type"))));
            countsBySource.merge(source, 1, Integer::sum);
        }
        return countsBySource;
    }

    private static Map<String, Object> buildPayload(String query, boolean broaderSearch) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("q", query);
        payload.put("query", query);
        payload.put("limit_per_source", broaderSearch ? 10 : 6);
        // buildIndicatorBundle() below only ever reads "verified"/"possible" from the deep-search
        // result - never "answer"/"story"/"ai_result_layer" - so the LLM call CatalogDeepSearchService
        // would otherwise make to write a narrative (see attachSearchAnswer) is pure wasted latency
        // on this path.
        payload.put("use_ai_story", false);
        // Manager Explorer is a decision surface: keep foreign-geo source coverage broad enough for
        // FRED/ECB/IMF and always inject a short macro/production probe scaffold so sector-only LLM
        // phrases (e.g. "CAPEX výroby počítačů") cannot empty those lanes.
        payload.put("manager_discovery", true);
        payload.put("extra_index_probe_terms", ExploreManagerDiscoveryTerms.probeTermsFor(query));
        // Bez tohohle vracel Explorer 1-7 ukazatelů podle formulace dotazu. Search V2 ověřuje
        // živým náhledem nejvýš MAX_PREVIEW_VERIFY = 8 kandidátů a ve výchozím režimu "full"
        // zahodí všechno ostatní, takže `verified` byl zároveň celý výsledek. V režimu
        // "top_preview" zůstane ověřování stejné (těch 8 nejlepších), ale zbytek ranku se
        // vrátí jako `possible` - buildIndicatorBundle je použije až po ověřených.
        //
        // Naměřeno na "Jak si vede bankovní sektor v Česku…": results 6 -> 24
        // (verified 6, possible 18), a to o něco rychleji (11,4 s -> 8,0 s).
        // V1 tyhle klíče ignoruje (reaguje jen na preview_mode=metadata_only).
        return payload;
    }

    /**
     * Buckets each hit into {@code sectorRows} (genuinely on-topic for THIS query, and not tagged to a
     * conflicting country) vs {@code macroRows} (generic macro/FX/production scaffold, or right-topic
     * but wrong-geo). The old version bucketed purely by catalog source ({@code isMacroSource}), and
     * every source Explorer ever queries (arad/csu/eurostat/ecb/fred/imf/oecd/bis/data360) is a "macro"
     * source - so sectorRows was ALWAYS empty and the "sectorRows.isEmpty() -> take macroRows[0:4]"
     * fallback fired on every single query, meaning "sector_indicators" (= recommended_chart_set, the
     * headline chart set) was never actually sector-selected at all - it was just "whatever ranked
     * first overall", macro filler included. This uses two content-based signals that already exist
     * and are already computed earlier in the same request:
     * {@link ExploreManagerDiscoveryTerms#isMacroScaffoldRow} (GDP/inflation/unemployment/rates/FX/
     * generic industrial-production - the deliberate macro backdrop CatalogDeepSearchService force-
     * keeps for manager_discovery requests) and {@link SearchResultCanonicalMetadataService}'s
     * per-hit {@code canonical_geo_codes}/{@code geo_scope} (e.g. a hit tagged single-country CZ
     * cannot answer a query about Slovakia, no matter how on-topic its title is).
     */
    private IndicatorBundle buildIndicatorBundle(
            Map<String, Object> result, String sector, String query, List<String> resolvedCountryCodes) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> verified = (List<Map<String, Object>>) result.getOrDefault("verified", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> possible = (List<Map<String, Object>>) result.getOrDefault("possible", List.of());
        // Blízké duplikáty se konsolidují PŘED rozpočtem 12 míst, ne až po něm.
        // Naměřeno na "Ziskovost bank a objem úvěrů v ČR": tři řádky se shodným názvem
        // "měnové finanční instituce (banky)" (různé dataset_id) a dva "Banky - Výkaz zisku
        // a ztráty" zabraly pět z dvanácti slotů, takže se do finálního setu nevešly úvěrové
        // řady vůbec. Uživatel navíc viděl v seznamu dvakrát tentýž ukazatel.
        //
        // Konsoliduje se zvlášť ve `verified` a `possible`, aby si ověřené řady udržely
        // přednost - consolidateDisplayRows uvnitř řadí podle skóre, takže jedno společné
        // volání by nechalo vysoko skórující "possible" předběhnout "verified".
        List<Map<String, Object>> merged =
                new ArrayList<>(CatalogSearchVariantDedup.consolidateDisplayRows(verified));
        if (merged.size() > 12) {
            merged = new ArrayList<>(merged.subList(0, 12));
        }
        Set<String> mergedSignatures = new LinkedHashSet<>();
        for (Map<String, Object> row : merged) {
            String signature = CatalogSearchVariantDedup.displaySignature(row);
            if (!signature.isBlank()) {
                mergedSignatures.add(signature);
            }
        }
        for (Map<String, Object> row : CatalogSearchVariantDedup.consolidateDisplayRows(possible)) {
            if (merged.size() >= 12) {
                break;
            }
            String signature = CatalogSearchVariantDedup.displaySignature(row);
            if (!signature.isBlank() && !mergedSignatures.add(signature)) {
                continue;
            }
            merged.add(row);
        }
        List<String> detectedCountryCodes = GeoIntentSnapshot.fromDetection(query).countryCodes().stream()
                .map(code -> code.toUpperCase(Locale.ROOT))
                .toList();
        // A continent-level question ("Evropa") names no single country in its own text, so
        // detection above comes back empty - fall back to the caller's already-resolved target
        // geo (e.g. ExploreGeoResolver's continent member list) so a generic macro-scaffold row
        // pinned to a country outside that set (US FRED "Industrial Production" for a Europe
        // question) can still be recognized as geo-irrelevant below.
        List<String> queryCountryCodes = !detectedCountryCodes.isEmpty() ? detectedCountryCodes : resolvedCountryCodes;
        List<Map<String, Object>> sectorRows = new ArrayList<>();
        List<Map<String, Object>> macroRows = new ArrayList<>();
        for (Map<String, Object> hit : merged) {
            Map<String, Object> canonicalHit = canonicalMetadataService.enrich(hit);
            boolean geoConflict = hasGeoConflict(canonicalHit, queryCountryCodes);
            boolean topicIntentMatch = ExploreManagerDiscoveryTerms.isTopicIntentRow(query, canonicalHit);
            // A query directly ABOUT unemployment/GDP/inflation (e.g. "nezaměstnanost v Německu") must
            // not have its own answer demoted to macro backdrop just because "unemployment"/"gdp" is
            // also on the macro-scaffold needle list. CatalogDeepSearchFinalRanker already computed
            // whether this row matched a term the query planner extracted from THIS query's own text
            // (topic_hit_count/topic_tokens, non-blind); when it did, the row is genuinely responsive,
            // not incidental backdrop.
            boolean queryOwnTopicMatch = isGenuineQueryTopicMatch(canonicalHit);
            // Topic overlap only rescues a macro-scaffold row from demotion when its OWN geography
            // does not conflict with the query - a wrong-country row sharing keywords with the
            // query (e.g. US "Industrial Production: Manufacturing" for a Germany/Italy factory
            // question, which also matches the "production" intent's own "manufactur" keep-needle)
            // is never the right answer no matter how many words it shares. Confirmed live: without
            // this, geoConflict correctly excluded it from sectorIndicators, but topicIntentMatch
            // ALSO flipped macroScaffold to false, so it skipped the geo-based drop below entirely
            // and still landed in macroIndicators as unfiltered "backdrop".
            boolean topicRescueApplies = !geoConflict && (topicIntentMatch || queryOwnTopicMatch);
            boolean macroScaffold = ExploreManagerDiscoveryTerms.isMacroScaffoldRow(canonicalHit) && !topicRescueApplies;
            // A row pinned to ONE specific country on a KNOWN different continent than a
            // multi-country continent target has zero decision value (e.g. US FRED "Industrial
            // Production" surfacing for a "Evropa" question). Drop it outright rather than letting
            // it fall through to macroRows as if it were legitimate continent backdrop.
            // isKnownDifferentContinent only fires for a country it can positively place on a
            // DIFFERENT continent (via ExploreGeoResolver's own curated member lists) - a
            // same-continent country simply outside that short "major economies" list (e.g.
            // Portugal for Europe) is left alone and keeps the existing demote-to-macro behavior
            // below, since that case is legitimate neighboring-country context.
            if (shouldDropGeoIrrelevantScaffold(canonicalHit, queryCountryCodes, macroScaffold, geoConflict)) {
                continue;
            }
            // Tried also requiring domain_match/metric_match as a hard gate for EVERY row (to catch
            // generic "industry turnover" leaking through for a narrow "automotive" query) - reverted
            // after live testing showed it demoting genuinely on-topic hits for OTHER queries (e.g.
            // "Tier-1 kapitálová přiměřenost bank" and "Nefunkční úvěry v bankovnictví" both pushed to
            // macro for a plain bank-profitability query, with only one bank series surviving as
            // "sector"). Used here only as a narrow escape hatch for rows already macro-scaffold-
            // flagged, not as a requirement every row must clear.
            boolean isSector = !macroScaffold && !geoConflict;
            Map<String, Object> indicator = toIndicator(canonicalHit, sector, isSector);
            if (isSector) {
                sectorRows.add(indicator);
            } else {
                macroRows.add(indicator);
            }
        }
        // No "steal from macro to fill sector" fallback: if nothing topically/geographically matches,
        // sectorRows stays honestly empty rather than relabeling generic GDP/inflation/FX as "the
        // automotive answer" (which is the exact bug this bucketing replaces). ExploreSectorContract
        // surfaces an explicit hint to the user for this case instead of silently faking a match.
        @SuppressWarnings("unchecked")
        Map<String, Object> profile = result.get("performance_profile") instanceof Map<?, ?> rawProfile
                ? new LinkedHashMap<>((Map<String, Object>) rawProfile)
                : Map.of();
        return new IndicatorBundle(sectorRows, macroRows, merged.size(), false, 0L, 0L, profile);
    }

    /**
     * True when {@code CatalogDeepSearchFinalRanker} found this row matched a term the query planner
     * actually extracted from THIS query's text (non-blind, non-empty topic profile with at least one
     * hit) - as opposed to the profile having nothing to say about it. Used only to keep an otherwise
     * macro-scaffold-flagged row (GDP/unemployment/inflation/rates title) from being demoted when the
     * query itself is directly about that exact topic (e.g. "nezaměstnanost v Německu").
     */
    private static boolean isGenuineQueryTopicMatch(Map<String, Object> hit) {
        Object topicTokens = hit.get("topic_tokens");
        boolean hasRealProfile = topicTokens instanceof List<?> tokens && !tokens.isEmpty();
        if (!hasRealProfile) {
            return false;
        }
        Object hitCount = hit.get("topic_hit_count");
        return hitCount instanceof Number number && number.intValue() > 0;
    }

    /** True when the hit is pinned to a single country that conflicts with the query's resolved geo. */
    private static boolean hasGeoConflict(Map<String, Object> hit, List<String> queryCountryCodes) {
        if (queryCountryCodes.isEmpty()) {
            return false;
        }
        String hitCountry = singleCountryCodeOf(hit);
        return !hitCountry.isBlank() && !queryCountryCodes.contains(hitCountry);
    }

    /**
     * True when the hit's own single country (already established to conflict with {@code
     * queryCountryCodes} via {@link #hasGeoConflict}) is on a KNOWN different continent than the
     * target - see {@link ExploreGeoResolver#isKnownDifferentContinent}. Only meaningful for a
     * genuinely multi-country target (a continent's member list); the caller gates on {@code
     * queryCountryCodes.size() > 1} before calling this.
     */
    private static boolean isCrossContinentConflict(Map<String, Object> hit, List<String> queryCountryCodes) {
        String hitCountry = singleCountryCodeOf(hit);
        if (hitCountry.isBlank()) {
            return false;
        }
        return ExploreGeoResolver.isKnownDifferentContinent(hitCountry, queryCountryCodes.get(0));
    }

    /**
     * True when a macro-scaffold row pinned to ONE specific country should be dropped outright
     * rather than kept as generic "backdrop":
     *
     * <p>- No target country/continent at all: {@link #hasGeoConflict} always returns {@code
     * false} here (nothing to conflict WITH), so without this check every single-country
     * statistic sails through unfiltered - confirmed live: a country-less "should I invest in
     * construction or cars" question surfaced Morocco/Argentina/Australia/UAE consumer-price
     * statistics as generic global context, with zero connection to the query. There is no
     * query-derived reason it was that ONE country and not any of the ~190 others, so it is
     * dropped outright.
     *
     * <p>- A genuinely multi-country (continent) target: only a KNOWN cross-continent conflict
     * ({@link #isCrossContinentConflict}) is dropped; a same-continent country outside the
     * curated "major economies" list (e.g. Portugal for a Europe question) is legitimate
     * neighboring context (existing behavior, unchanged).
     *
     * <p>- A single specific target country: existing long-standing behavior is unchanged - a
     * conflicting row (e.g. US industrial production for a Germany question) still lands in
     * macroIndicators as comparative backdrop - see {@code
     * ExploreDiscoveryServiceTest#macroScaffoldRowPinnedToAConflictingCountryStillLandsInMacroForAPlainSingleCountryQuery}.
     */
    private static boolean shouldDropGeoIrrelevantScaffold(
            Map<String, Object> hit, List<String> queryCountryCodes, boolean macroScaffold, boolean geoConflict) {
        if (!macroScaffold) {
            return false;
        }
        if (queryCountryCodes.isEmpty()) {
            return !singleCountryCodeOf(hit).isBlank();
        }
        if (geoConflict && queryCountryCodes.size() > 1) {
            return isCrossContinentConflict(hit, queryCountryCodes);
        }
        return false;
    }

    private static String singleCountryCodeOf(Map<String, Object> hit) {
        if ("single_country".equals(String.valueOf(hit.get("geo_scope")))) {
            Object rawCodes = hit.get("canonical_geo_codes");
            if (rawCodes instanceof List<?> codes && !codes.isEmpty()) {
                return String.valueOf(codes.get(0)).toUpperCase(Locale.ROOT);
            }
        }
        return knownSingleCountryFredOverride(hit);
    }

    /**
     * Fallback for the handful of FRED series known to be single-country (US) national statistics
     * despite carrying no per-row geo dimension the canonical metadata service could have used -
     * see {@link ExploreManagerDiscoveryTerms#KNOWN_SINGLE_COUNTRY_FRED_SERIES}. Without this,
     * {@link #hasGeoConflict} always reports "no conflict" for these rows (their own {@code
     * geo_scope} is "unknown"), so they pass through as unfiltered "generic backdrop" for ANY
     * query regardless of target country.
     */
    private static String knownSingleCountryFredOverride(Map<String, Object> hit) {
        String source = CatalogSourceRegistry.normalizeSearchSource(
                String.valueOf(hit.getOrDefault("canonical_source_id", hit.get("source"))));
        if (!"fred".equals(source)) {
            return "";
        }
        String setId = String.valueOf(hit.getOrDefault("set_id", hit.getOrDefault("dataset_id", "")))
                .trim()
                .toUpperCase(Locale.ROOT);
        return ExploreManagerDiscoveryTerms.KNOWN_SINGLE_COUNTRY_FRED_SERIES.getOrDefault(setId, "");
    }

    @FunctionalInterface
    public interface LaneConsumer {
        void onLane(String source, Map<String, Object> lanePayload);
    }

    public record IndicatorBundle(
            List<Map<String, Object>> sectorIndicators,
            List<Map<String, Object>> macroIndicators,
            int totalCandidates,
            boolean cacheHit,
            long servingTimeMs,
            long cachedComputeTimeMs,
            Map<String, Object> discoveryProfile) {
        public IndicatorBundle {
            discoveryProfile = discoveryProfile == null ? Map.of() : Map.copyOf(discoveryProfile);
        }

        public IndicatorBundle(
                List<Map<String, Object>> sectorIndicators,
                List<Map<String, Object>> macroIndicators,
                int totalCandidates,
                boolean cacheHit,
                long servingTimeMs,
                long cachedComputeTimeMs) {
            this(
                    sectorIndicators,
                    macroIndicators,
                    totalCandidates,
                    cacheHit,
                    servingTimeMs,
                    cachedComputeTimeMs,
                    Map.of());
        }

        public IndicatorBundle(
                List<Map<String, Object>> sectorIndicators,
                List<Map<String, Object>> macroIndicators,
                int totalCandidates,
                boolean cacheHit,
                long servingTimeMs) {
            this(sectorIndicators, macroIndicators, totalCandidates, cacheHit, servingTimeMs, 0L);
        }

        static IndicatorBundle empty() {
            return new IndicatorBundle(List.of(), List.of(), 0, false, 0L);
        }

        IndicatorBundle withTiming(boolean cacheHit, long servingTimeMs, long cachedComputeTimeMs) {
            return new IndicatorBundle(
                    sectorIndicators,
                    macroIndicators,
                    totalCandidates,
                    cacheHit,
                    servingTimeMs,
                    cachedComputeTimeMs,
                    discoveryProfile);
        }
    }

    private static Map<String, Object> toIndicator(Map<String, Object> hit, String sector, boolean isSector) {
        Map<String, Object> row = new LinkedHashMap<>();
        String source = CatalogSourceRegistry.normalizeSearchSource(String.valueOf(hit.get("source")));
        row.put("source", source);
        row.put("dataset_id", hit.get("set_id"));
        row.put("set_id", hit.get("set_id"));
        row.put("indicator_name", hit.getOrDefault("title", hit.get("name")));
        row.put("title", hit.getOrDefault("title", hit.get("name")));
        row.put("full_path", hit.get("full_path"));
        row.put("why_relevant", hit.get("why_relevant"));
        row.put("manager_category", isSector ? "sector_indicators" : "macro_indicators");
        row.put("confidence_score", hit.getOrDefault("_search_score", 0.7));
        row.put("from_preset", false);
        row.put("sector_hint", sector);
        for (String key : List.of(
                "canonical_source_id",
                "canonical_geo_codes",
                "canonical_sector_ids",
                "canonical_metric_intents",
                "geo_scope",
                "sector_scope",
                "canonical_metadata_provenance")) {
            if (hit.containsKey(key)) {
                row.put(key, hit.get(key));
            }
        }
        return row;
    }
}
