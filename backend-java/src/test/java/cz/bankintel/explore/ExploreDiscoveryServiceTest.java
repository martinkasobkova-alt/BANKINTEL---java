package cz.bankintel.explore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.CatalogDeepSearchService;
import cz.bankintel.search.v2.normalization.SearchResultCanonicalMetadataService;
import cz.bankintel.search.v2.ontology.SearchV2InstitutionalSectorRegistry;
import cz.bankintel.search.v2.ontology.SearchV2MetricIntentRegistry;
import cz.bankintel.search.v2.orchestration.SearchV2FeatureFlags;
import cz.bankintel.search.v2.orchestration.SearchV2Service;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Regression tests for the ETAPA 2 fix: {@code CatalogDeepSearchService.deepSearch(request)} is
 * implemented as {@code deepSearchWithLanes(request, null)} - the exact same computation - so
 * {@link ExploreDiscoveryService#discoverWithLanes} must return the fully processed {@link
 * ExploreDiscoveryService.IndicatorBundle} from THAT SAME call, instead of discarding it (as it
 * did before) and forcing a caller to invoke {@link ExploreDiscoveryService#discover} again
 * afterwards for the real result.
 */
class ExploreDiscoveryServiceTest {

    /** A fresh, un-warmed cache mock - Mockito's default answer for Optional-returning methods
     * is Optional.empty(), so every lookup misses, matching a real cold ExploreDiscoveryCache. */
    private static ExploreDiscoveryCache noopCache() {
        return mock(ExploreDiscoveryCache.class);
    }

    /**
     * These tests drive the V1 engine through a mocked {@link CatalogDeepSearchService}. A default
     * {@link SearchV2FeatureFlags} reports version "v1" (its @Value fields are unset here), so
     * ExploreDiscoveryService keeps taking the V1 branch and the V2 service is never touched.
     */
    private static SearchV2FeatureFlags v1Flags() {
        return new SearchV2FeatureFlags();
    }

    private static SearchResultCanonicalMetadataService canonicalMetadataService() {
        ObjectMapper objectMapper = new ObjectMapper();
        return new SearchResultCanonicalMetadataService(
                new SearchV2InstitutionalSectorRegistry(objectMapper),
                new SearchV2MetricIntentRegistry(objectMapper));
    }

    private static Map<String, Object> hit(String source, String setId, String title) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("source", source);
        row.put("set_id", setId);
        row.put("title", title);
        return row;
    }

    @Test
    void discoverWithLanesReturnsIndicatorBundleFromItsOwnDeepSearchCall() {
        CatalogDeepSearchService deepSearch = mock(CatalogDeepSearchService.class);
        Map<String, Object> deepSearchResult = Map.of(
                "verified", List.of(hit("arad", "ARAD_1", "ARAD indicator")),
                // A genuine macro-scaffold title (GDP) so it lands in macroIndicators under the
                // content-based bucketing (ExploreManagerDiscoveryTerms.isMacroScaffoldRow), not
                // because "fred" is treated as a de facto macro source.
                "possible", List.of(hit("fred", "FRED_1", "Gross domestic product (GDP)")));
        when(deepSearch.deepSearchWithLanes(anyMap(), any())).thenReturn(deepSearchResult);

        ExploreDiscoveryService service = new ExploreDiscoveryService(deepSearch, noopCache(), canonicalMetadataService(), v1Flags(), mock(SearchV2Service.class));

        ExploreDiscoveryService.IndicatorBundle bundle = service.discoverWithLanes(
                "jak se vyviji hypoteky", "banking_finance", false, (source, lane) -> {});

        // Real indicators from the lane call's own result, not an empty/discarded bundle.
        assertEquals(2, bundle.totalCandidates());
        assertTrue(bundle.sectorIndicators().stream().anyMatch(row -> "ARAD_1".equals(row.get("set_id"))));
        assertTrue(bundle.macroIndicators().stream().anyMatch(row -> "FRED_1".equals(row.get("set_id"))));

        // Must call deepSearchWithLanes exactly once, and never the plain deepSearch(request)
        // overload - a caller relying on discoverWithLanes's return value must never need to
        // trigger a second, independent deep-search computation for the same request.
        verify(deepSearch, times(1)).deepSearchWithLanes(anyMap(), any());
        verify(deepSearch, times(0)).deepSearch(anyMap());
    }

    @Test
    void discoverAndDiscoverWithLanesProduceIdenticalIndicatorsForTheSameDeepSearchResult() {
        CatalogDeepSearchService deepSearchA = mock(CatalogDeepSearchService.class);
        CatalogDeepSearchService deepSearchB = mock(CatalogDeepSearchService.class);
        Map<String, Object> result = Map.of(
                "verified", List.of(hit("fred", "FRED_1", "Fed indicator")),
                "possible", List.of());
        when(deepSearchA.deepSearch(anyMap())).thenReturn(result);
        when(deepSearchB.deepSearchWithLanes(anyMap(), any())).thenReturn(result);

        ExploreDiscoveryService viaDiscover = new ExploreDiscoveryService(deepSearchA, noopCache(), canonicalMetadataService(), v1Flags(), mock(SearchV2Service.class));
        ExploreDiscoveryService viaLanes = new ExploreDiscoveryService(deepSearchB, noopCache(), canonicalMetadataService(), v1Flags(), mock(SearchV2Service.class));

        ExploreDiscoveryService.IndicatorBundle a = viaDiscover.discover("inflace", "macro_economy", false);
        ExploreDiscoveryService.IndicatorBundle b =
                viaLanes.discoverWithLanes("inflace", "macro_economy", false, (source, lane) -> {});

        assertEquals(a.totalCandidates(), b.totalCandidates());
        assertEquals(a.sectorIndicators(), b.sectorIndicators());
        assertEquals(a.macroIndicators(), b.macroIndicators());
    }

    @Test
    void discoverCanonicalizesLegacyDeepSearchRowsBeforeBuildingManagerIndicators() {
        CatalogDeepSearchService deepSearch = mock(CatalogDeepSearchService.class);
        Map<String, Object> legacyHit = hit("EUROSTAT", "tipsbd40", "Return on equity of banks");
        legacyHit.put("geo", "AUT");
        legacyHit.put("institutional_sector", "banking sector");
        legacyHit.put("primary_metric", "profitability");
        when(deepSearch.deepSearch(anyMap()))
                .thenReturn(Map.of("verified", List.of(legacyHit), "possible", List.of()));

        ExploreDiscoveryService service =
                new ExploreDiscoveryService(deepSearch, noopCache(), canonicalMetadataService(), v1Flags(), mock(SearchV2Service.class));
        // "Return on equity of banks" is genuinely on-topic for "bank profitability" and is not a
        // macro-scaffold row (GDP/inflation/unemployment/rates/FX/generic industrial production), so
        // it belongs in sectorIndicators, not macroIndicators - see ExploreDiscoveryService's content-
        // based bucketing (it used to land in "macro" purely because Eurostat was treated as a de
        // facto macro source, regardless of what the series was actually about).
        Map<String, Object> indicator = service.discover("bank profitability", "banking_finance", false)
                .sectorIndicators()
                .getFirst();

        assertEquals("eurostat", indicator.get("canonical_source_id"));
        assertEquals(List.of("AT"), indicator.get("canonical_geo_codes"));
        assertEquals(List.of("banks"), indicator.get("canonical_sector_ids"));
        assertEquals(List.of("profitability"), indicator.get("canonical_metric_intents"));
        assertTrue(indicator.containsKey("canonical_metadata_provenance"));
    }

    // The following three tests cover the fix for a real relevance bug: sector-vs-macro bucketing
    // used to be decided purely by which catalog source a hit came from (isMacroSource), and every
    // source Explorer queries (arad/csu/eurostat/ecb/fred/imf/oecd/bis/data360) counted as "macro" -
    // so sectorIndicators was ALWAYS empty and the "take macroIndicators[0:4]" fallback fired on
    // every single request, meaning the headline chart set was never actually sector-selected. Live
    // testing on "Jaký je vývoj automobilového průmyslu na Slovensku?" showed zero Slovak/automotive
    // series among the 4 "sector indicators" - just generic EU industrial turnover, GDP and an
    // FX rate. The fix buckets by content (ExploreManagerDiscoveryTerms.isMacroScaffoldRow / geo
    // conflict) instead of by source.

    @Test
    void genuinelyOnTopicHitFromAMacroSourceStillLandsInSectorIndicators() {
        CatalogDeepSearchService deepSearch = mock(CatalogDeepSearchService.class);
        // eurostat is one of the sources every hit used to be forced into "macro" for, regardless of
        // topic - this dataset is genuinely about car registrations, not GDP/inflation/FX/etc.
        Map<String, Object> carRegistrations = hit("eurostat", "road_eqr_carpda", "Automobilový průmysl — nové registrace vozidel");
        when(deepSearch.deepSearchWithLanes(anyMap(), any()))
                .thenReturn(Map.of("verified", List.of(carRegistrations), "possible", List.of()));

        ExploreDiscoveryService service =
                new ExploreDiscoveryService(deepSearch, noopCache(), canonicalMetadataService(), v1Flags(), mock(SearchV2Service.class));
        ExploreDiscoveryService.IndicatorBundle bundle = service.discoverWithLanes(
                "Jaký je vývoj automobilového průmyslu?", "automotive", false, (source, lane) -> {});

        assertTrue(
                bundle.sectorIndicators().stream().anyMatch(row -> "road_eqr_carpda".equals(row.get("set_id"))),
                "on-topic automotive dataset must be a sector indicator, not macro, regardless of source");
        assertTrue(bundle.macroIndicators().isEmpty());
    }

    @Test
    void macroScaffoldRowIsNeverMisreportedAsASectorIndicator() {
        CatalogDeepSearchService deepSearch = mock(CatalogDeepSearchService.class);
        Map<String, Object> gdp = hit("eurostat", "nama_10_gdp", "Gross domestic product (GDP)");
        when(deepSearch.deepSearchWithLanes(anyMap(), any()))
                .thenReturn(Map.of("verified", List.of(gdp), "possible", List.of()));

        ExploreDiscoveryService service =
                new ExploreDiscoveryService(deepSearch, noopCache(), canonicalMetadataService(), v1Flags(), mock(SearchV2Service.class));
        ExploreDiscoveryService.IndicatorBundle bundle = service.discoverWithLanes(
                "Jaký je vývoj automobilového průmyslu?", "automotive", false, (source, lane) -> {});

        assertTrue(bundle.macroIndicators().stream().anyMatch(row -> "nama_10_gdp".equals(row.get("set_id"))));
        // GDP alone must not be laundered into "the automotive answer" via the empty-sector fallback.
        assertTrue(bundle.sectorIndicators().stream().noneMatch(row -> "nama_10_gdp".equals(row.get("set_id"))));
    }

    @Test
    void gdpRowIsASectorIndicatorWhenTheQueryIsDirectlyAboutGdp() {
        // "GDP"/"unemployment" titles are on the macro-scaffold needle list (they're backdrop for an
        // unrelated query like automotive production), but when the query IS "Jak se vyvíjí HDP v
        // Polsku?", the GDP row is the actual answer, not incidental context. CatalogDeepSearchFinalRanker
        // already stamps every row with whether it matched a term the planner extracted from the query's
        // OWN text (topic_tokens/topic_hit_count) - this must override the macro-scaffold demotion.
        CatalogDeepSearchService deepSearch = mock(CatalogDeepSearchService.class);
        Map<String, Object> gdp = hit("eurostat", "nama_10_gdp", "Gross domestic product (GDP)");
        gdp.put("topic_tokens", List.of("hdp"));
        gdp.put("topic_hit_count", 1);
        when(deepSearch.deepSearchWithLanes(anyMap(), any()))
                .thenReturn(Map.of("verified", List.of(gdp), "possible", List.of()));

        ExploreDiscoveryService service =
                new ExploreDiscoveryService(deepSearch, noopCache(), canonicalMetadataService(), v1Flags(), mock(SearchV2Service.class));
        ExploreDiscoveryService.IndicatorBundle bundle = service.discoverWithLanes(
                "Jak se vyvíjí HDP v Polsku?", "macro_economy", false, (source, lane) -> {});

        assertTrue(
                bundle.sectorIndicators().stream().anyMatch(row -> "nama_10_gdp".equals(row.get("set_id"))),
                "GDP must be the sector answer when the query is literally asking about GDP");
        assertTrue(bundle.macroIndicators().stream().noneMatch(row -> "nama_10_gdp".equals(row.get("set_id"))));
    }

    @Test
    void onTopicHitPinnedToAConflictingCountryIsExcludedFromSectorIndicators() {
        CatalogDeepSearchService deepSearch = mock(CatalogDeepSearchService.class);
        Map<String, Object> czechCarProduction =
                hit("csu", "prum_c29_cz", "Index průmyslové produkce — C29 automobilový (ČSÚ)");
        czechCarProduction.put("geo", "CZE");
        when(deepSearch.deepSearchWithLanes(anyMap(), any()))
                .thenReturn(Map.of("verified", List.of(czechCarProduction), "possible", List.of()));

        ExploreDiscoveryService service =
                new ExploreDiscoveryService(deepSearch, noopCache(), canonicalMetadataService(), v1Flags(), mock(SearchV2Service.class));
        ExploreDiscoveryService.IndicatorBundle bundle = service.discoverWithLanes(
                "Jaký je vývoj automobilového průmyslu na Slovensku?", "automotive", false, (source, lane) -> {});

        assertTrue(
                bundle.sectorIndicators().stream().noneMatch(row -> "prum_c29_cz".equals(row.get("set_id"))),
                "a Czech-only series must not stand in as the answer for a Slovakia-specific question");
    }

    // Regression coverage for the "Evropa" continent-macro fix: a generic macro-scaffold row
    // pinned to ONE specific country outside the query's target continent (live example: FRED's
    // US "Industrial Production Index" showing up as macro backdrop for a Europe-wide production
    // question) has zero decision value and must be dropped entirely, not merely excluded from
    // sectorIndicators. Query text detection alone (GeoIntentSnapshot) finds no country in
    // "Evropa"-style phrasing, so the caller's already-resolved continent member list (from
    // ExploreGeoResolver) is threaded through as a fallback for exactly this case.

    @Test
    void macroScaffoldRowPinnedToACountryOutsideTheTargetContinentIsDroppedEntirely() {
        CatalogDeepSearchService deepSearch = mock(CatalogDeepSearchService.class);
        Map<String, Object> usIndustrialProduction = hit("fred", "INDPRO", "Industrial Production Index");
        usIndustrialProduction.put("geo", "USA");
        when(deepSearch.deepSearchWithLanes(anyMap(), any()))
                .thenReturn(Map.of("verified", List.of(usIndustrialProduction), "possible", List.of()));

        ExploreDiscoveryService service =
                new ExploreDiscoveryService(deepSearch, noopCache(), canonicalMetadataService(), v1Flags(), mock(SearchV2Service.class));
        List<String> europeMembers = List.of("CZ", "DE", "AT", "PL", "SK", "FR", "IT", "ES", "NL", "BE");
        ExploreDiscoveryService.IndicatorBundle bundle = service.discoverWithLanes(
                "Jak si stojí výroba v Evropě?", "manufacturing", false, europeMembers, (source, lane) -> {});

        assertTrue(
                bundle.sectorIndicators().stream().noneMatch(row -> "INDPRO".equals(row.get("set_id"))),
                "US-only macro scaffold must not stand in as sector data for a Europe question");
        assertTrue(
                bundle.macroIndicators().stream().noneMatch(row -> "INDPRO".equals(row.get("set_id"))),
                "US-only macro scaffold is geo-irrelevant noise for a Europe question, not backdrop");
    }

    @Test
    void macroScaffoldRowPinnedToAConflictingCountryStillLandsInMacroForAPlainSingleCountryQuery() {
        // Existing single-country behavior (queryCountryCodes.size() == 1) must stay exactly as
        // before - the new exclusion is gated on a genuinely multi-country (continent) target.
        CatalogDeepSearchService deepSearch = mock(CatalogDeepSearchService.class);
        Map<String, Object> usIndustrialProduction = hit("fred", "INDPRO", "Industrial Production Index");
        usIndustrialProduction.put("geo", "USA");
        when(deepSearch.deepSearchWithLanes(anyMap(), any()))
                .thenReturn(Map.of("verified", List.of(usIndustrialProduction), "possible", List.of()));

        ExploreDiscoveryService service =
                new ExploreDiscoveryService(deepSearch, noopCache(), canonicalMetadataService(), v1Flags(), mock(SearchV2Service.class));
        ExploreDiscoveryService.IndicatorBundle bundle = service.discoverWithLanes(
                "Jak si stojí výroba v Německu?", "manufacturing", false, List.of("DE"), (source, lane) -> {});

        assertTrue(bundle.macroIndicators().stream().anyMatch(row -> "INDPRO".equals(row.get("set_id"))));
    }

    @Test
    void macroScaffoldRowPinnedToAnUnrelatedCountryIsDroppedWhenNoTargetCountryIsResolved() {
        // No target country/continent at all (e.g. "Ma smysl investovat do stavebnictví nebo
        // autovýroby?" - no geo mentioned anywhere): a single-country statistic pinned to some
        // OTHER country has no basis for inclusion as generic "backdrop" - confirmed live with
        // Morocco/Argentina/Australia/UAE consumer-price statistics surfacing for a country-less
        // question with zero connection to it.
        CatalogDeepSearchService deepSearch = mock(CatalogDeepSearchService.class);
        Map<String, Object> croatiaCpi = hit("eurostat", "prc_hicp_manr_hr", "Croatia · Consumer prices statistics");
        croatiaCpi.put("geo", "HRV");
        when(deepSearch.deepSearchWithLanes(anyMap(), any()))
                .thenReturn(Map.of("verified", List.of(croatiaCpi), "possible", List.of()));

        ExploreDiscoveryService service =
                new ExploreDiscoveryService(deepSearch, noopCache(), canonicalMetadataService(), v1Flags(), mock(SearchV2Service.class));
        ExploreDiscoveryService.IndicatorBundle bundle = service.discoverWithLanes(
                "Ma smysl investovat do stavebnictvi?", "stavebnictví", false, (source, lane) -> {});

        assertTrue(
                bundle.macroIndicators().stream().noneMatch(row -> "prc_hicp_manr_hr".equals(row.get("set_id"))),
                "an unrelated country's CPI has no basis to appear as backdrop for a country-less question");
        assertTrue(bundle.sectorIndicators().stream().noneMatch(row -> "prc_hicp_manr_hr".equals(row.get("set_id"))));
    }

    @Test
    void ipmanFredSeriesWithNoOwnGeoFieldIsDroppedAsCrossContinentNoiseForAMultiCountryQuery() {
        // Real IPMAN/INDPRO rows carry no per-row geo dimension at all (unlike this test suite's
        // other FRED fixtures, which simulate an explicit "geo":"USA" field the real rows don't
        // have) - FRED is deliberately absent from geo_scopes.json's fixed_source_scopes (tagging
        // the whole source "US" would wrongly catch ECBMRRFR/DEXUSEU too), so without the
        // ExploreManagerDiscoveryTerms.KNOWN_SINGLE_COUNTRY_FRED_SERIES override this row's
        // geo_scope resolves to "unknown" and hasGeoConflict never fires - confirmed live:
        // IPMAN/INDPRO surfaced as unfiltered generic backdrop for "továrna v Německu nebo Itálii".
        CatalogDeepSearchService deepSearch = mock(CatalogDeepSearchService.class);
        Map<String, Object> ipman = hit("fred", "IPMAN", "Industrial Production: Manufacturing (NAICS)");
        when(deepSearch.deepSearchWithLanes(anyMap(), any()))
                .thenReturn(Map.of("verified", List.of(ipman), "possible", List.of()));

        ExploreDiscoveryService service =
                new ExploreDiscoveryService(deepSearch, noopCache(), canonicalMetadataService(), v1Flags(), mock(SearchV2Service.class));
        ExploreDiscoveryService.IndicatorBundle bundle = service.discoverWithLanes(
                "Ma smysl investovat do tovarny?", "Zpracovatelský průmysl", false, List.of("DE", "IT"), (source, lane) -> {});

        assertTrue(bundle.macroIndicators().stream().noneMatch(row -> "IPMAN".equals(row.get("set_id"))));
        assertTrue(bundle.sectorIndicators().stream().noneMatch(row -> "IPMAN".equals(row.get("set_id"))));
    }

    @Test
    void ipmanIsDroppedEvenWhenItsOwnTitleMatchesTheProductionIntentTopicNeedles() {
        // The exact live failure mode: enriching the search text with the resolved segment label
        // ("Zpracovatelský průmysl") activates the "production" intent, whose OWN topic keep-needle
        // "manufactur" ALSO matches IPMAN's title ("Industrial Production: Manufacturing") - so
        // isTopicIntentRow(query, hit) returns true purely from generic word overlap, nothing to
        // do with this row actually being about Germany or Italy. Before the geoConflict-before-
        // topic-rescue fix, that topic match flipped macroScaffold to false, which let the row
        // skip shouldDropGeoIrrelevantScaffold entirely and land in macroIndicators unfiltered -
        // confirmed live for "Ma smysl investovat do továrny v Německu nebo Itálii?".
        CatalogDeepSearchService deepSearch = mock(CatalogDeepSearchService.class);
        Map<String, Object> ipman = hit("fred", "IPMAN", "Industrial Production: Manufacturing (NAICS)");
        when(deepSearch.deepSearchWithLanes(anyMap(), any()))
                .thenReturn(Map.of("verified", List.of(ipman), "possible", List.of()));

        ExploreDiscoveryService service =
                new ExploreDiscoveryService(deepSearch, noopCache(), canonicalMetadataService(), v1Flags(), mock(SearchV2Service.class));
        ExploreDiscoveryService.IndicatorBundle bundle = service.discoverWithLanes(
                "Ma smysl investovat do tovarny v Nemecku nebo Italii? Zpracovatelský průmysl",
                "Zpracovatelský průmysl",
                false,
                List.of("DE", "IT"),
                (source, lane) -> {});

        assertTrue(bundle.macroIndicators().stream().noneMatch(row -> "IPMAN".equals(row.get("set_id"))));
        assertTrue(bundle.sectorIndicators().stream().noneMatch(row -> "IPMAN".equals(row.get("set_id"))));
    }

    @Test
    void fredRateAndFxMirrorsWithNoOwnGeoFieldStillLandInMacroForAMultiCountryQuery() {
        // The override must stay narrowly scoped to IPMAN/INDPRO - ECBMRRFR (ECB policy rate) and
        // DEXUSEU (USD/EUR) are deliberately geo-agnostic FRED mirrors (CORE_FRED_MACRO_SEEDS) and
        // must keep landing as backdrop for a multi-country EU query, not get swept up as "US".
        CatalogDeepSearchService deepSearch = mock(CatalogDeepSearchService.class);
        Map<String, Object> ecbRate = hit("fred", "ECBMRRFR", "ECB Main Refinancing Rate");
        Map<String, Object> usdEur = hit("fred", "DEXUSEU", "US Dollar to Euro Spot Exchange Rate");
        when(deepSearch.deepSearchWithLanes(anyMap(), any()))
                .thenReturn(Map.of("verified", List.of(ecbRate, usdEur), "possible", List.of()));

        ExploreDiscoveryService service =
                new ExploreDiscoveryService(deepSearch, noopCache(), canonicalMetadataService(), v1Flags(), mock(SearchV2Service.class));
        ExploreDiscoveryService.IndicatorBundle bundle = service.discoverWithLanes(
                "Ma smysl investovat do tovarny?", "Zpracovatelský průmysl", false, List.of("DE", "IT"), (source, lane) -> {});

        assertTrue(bundle.macroIndicators().stream().anyMatch(row -> "ECBMRRFR".equals(row.get("set_id"))));
        assertTrue(bundle.macroIndicators().stream().anyMatch(row -> "DEXUSEU".equals(row.get("set_id"))));
    }

    @Test
    void discoverWithLanesReturnsEmptyBundleWithoutCallingDeepSearchForBlankQuery() {
        CatalogDeepSearchService deepSearch = mock(CatalogDeepSearchService.class);
        ExploreDiscoveryService service = new ExploreDiscoveryService(deepSearch, noopCache(), canonicalMetadataService(), v1Flags(), mock(SearchV2Service.class));

        ExploreDiscoveryService.IndicatorBundle bundle =
                service.discoverWithLanes("", "", false, (source, lane) -> {});

        assertEquals(0, bundle.totalCandidates());
        verify(deepSearch, times(0)).deepSearchWithLanes(anyMap(), any());
    }

    // ETAPA 5: Explorer's IndicatorBundle only ever reads "verified"/"possible" from the deep-search
    // result (see buildIndicatorBundle) - it never displays "answer"/"story"/"ai_result_layer" - so
    // the LLM call CatalogDeepSearchService.attachSearchAnswer() would otherwise make on every
    // discovery request is pure wasted latency on this path. Both discover() and discoverWithLanes()
    // must request use_ai_story=false without needing to touch any other caller of deepSearch(WithLanes).
    @SuppressWarnings("unchecked")
    @Test
    void discoverWithLanesRequestsNoAiStoryFromDeepSearch() {
        CatalogDeepSearchService deepSearch = mock(CatalogDeepSearchService.class);
        when(deepSearch.deepSearchWithLanes(anyMap(), any())).thenReturn(Map.of("verified", List.of(), "possible", List.of()));
        ExploreDiscoveryService service =
                new ExploreDiscoveryService(deepSearch, noopCache(), canonicalMetadataService(), v1Flags(), mock(SearchV2Service.class));

        service.discoverWithLanes("jak se vyviji hypoteky", "banking_finance", false, (source, lane) -> {});

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(deepSearch).deepSearchWithLanes(payloadCaptor.capture(), any());
        assertEquals(false, payloadCaptor.getValue().get("use_ai_story"));
        assertEquals(true, payloadCaptor.getValue().get("manager_discovery"));
        assertTrue(
                !payloadCaptor.getValue().containsKey("sources"),
                "automatic discovery must let the query planner route sources");
        Object probes = payloadCaptor.getValue().get("extra_index_probe_terms");
        assertTrue(probes instanceof List<?> list && !list.isEmpty(), "manager discovery must inject macro probes");
    }

    @SuppressWarnings("unchecked")
    @Test
    void discoverRequestsNoAiStoryFromDeepSearch() {
        CatalogDeepSearchService deepSearch = mock(CatalogDeepSearchService.class);
        when(deepSearch.deepSearch(anyMap())).thenReturn(Map.of("verified", List.of(), "possible", List.of()));
        ExploreDiscoveryService service =
                new ExploreDiscoveryService(deepSearch, noopCache(), canonicalMetadataService(), v1Flags(), mock(SearchV2Service.class));

        service.discover("jak se vyviji hypoteky", "banking_finance", false);

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(deepSearch).deepSearch(payloadCaptor.capture());
        assertEquals(false, payloadCaptor.getValue().get("use_ai_story"));
        assertEquals(true, payloadCaptor.getValue().get("manager_discovery"));
        assertTrue(
                !payloadCaptor.getValue().containsKey("sources"),
                "automatic discovery must not masquerade the default source list as a user filter");
        Object probes = payloadCaptor.getValue().get("extra_index_probe_terms");
        assertTrue(probes instanceof List<?> list && list.contains("GDP"));
    }

    // ETAPA 6: a cache hit must skip CatalogDeepSearchService entirely (no re-planning, no
    // re-running the LLM) and report cacheHit=true - this is what makes repeat calls for the
    // identical query stable instead of re-running a planner whose search-term variants can
    // differ run to run.
    @Test
    void discoverReturnsCachedBundleWithoutCallingDeepSearchOnCacheHit() {
        CatalogDeepSearchService deepSearch = mock(CatalogDeepSearchService.class);
        ExploreDiscoveryCache cache = mock(ExploreDiscoveryCache.class);
        List<Map<String, Object>> cachedSector = List.of(hit("arad", "ARAD_CACHED", "Cached indicator"));
        when(cache.buildKey(any(), any(), anyBoolean())).thenReturn("some-key");
        when(cache.get("some-key"))
                .thenReturn(Optional.of(new ExploreDiscoveryCache.CachedEntry(cachedSector, List.of(), 1, 0L)));

        ExploreDiscoveryService service =
                new ExploreDiscoveryService(deepSearch, cache, canonicalMetadataService(), v1Flags(), mock(SearchV2Service.class));
        ExploreDiscoveryService.IndicatorBundle bundle = service.discover("ziskovost ceskych bank", "banking_finance", false);

        assertEquals(cachedSector, bundle.sectorIndicators());
        assertEquals(1, bundle.totalCandidates());
        assertTrue(bundle.cacheHit());
        verify(deepSearch, times(0)).deepSearch(anyMap());
        verify(cache, times(0)).put(any(), any(), any(), anyInt());
    }

    @Test
    void discoverStoresIntoCacheOnMissAndReportsCacheHitFalse() {
        CatalogDeepSearchService deepSearch = mock(CatalogDeepSearchService.class);
        ExploreDiscoveryCache cache = mock(ExploreDiscoveryCache.class);
        when(cache.buildKey(any(), any(), anyBoolean())).thenReturn("some-key");
        when(cache.get("some-key")).thenReturn(Optional.empty());
        when(deepSearch.deepSearch(anyMap())).thenReturn(Map.of(
                "verified", List.of(hit("fred", "FRED_1", "Fed indicator")), "possible", List.of()));

        ExploreDiscoveryService service =
                new ExploreDiscoveryService(deepSearch, cache, canonicalMetadataService(), v1Flags(), mock(SearchV2Service.class));
        ExploreDiscoveryService.IndicatorBundle bundle = service.discover("inflace v Cesku", "macro_economy", false);

        assertEquals(false, bundle.cacheHit());
        verify(deepSearch, times(1)).deepSearch(anyMap());
        verify(cache).put(
                eq("some-key"),
                eq(bundle.sectorIndicators()),
                eq(bundle.macroIndicators()),
                eq(1),
                anyLong());
    }

    // On a cache hit for the SSE/lane path, real per-source lane events (grouped from the cached
    // rows) must still be emitted - otherwise the progress UI would show nothing at all for an
    // instant cached response instead of an honest, near-instant per-source breakdown.
    @Test
    void discoverWithLanesEmitsSyntheticLaneEventsFromCachedRowsOnCacheHit() {
        CatalogDeepSearchService deepSearch = mock(CatalogDeepSearchService.class);
        ExploreDiscoveryCache cache = mock(ExploreDiscoveryCache.class);
        List<Map<String, Object>> cachedSector = List.of(
                hit("arad", "ARAD_1", "Indicator 1"), hit("arad", "ARAD_2", "Indicator 2"));
        List<Map<String, Object>> cachedMacro = List.of(hit("fred", "FRED_1", "Fed indicator"));
        when(cache.buildKey(any(), any(), anyBoolean())).thenReturn("some-key");
        when(cache.get("some-key"))
                .thenReturn(Optional.of(new ExploreDiscoveryCache.CachedEntry(cachedSector, cachedMacro, 3, 0L)));

        ExploreDiscoveryService service =
                new ExploreDiscoveryService(deepSearch, cache, canonicalMetadataService(), v1Flags(), mock(SearchV2Service.class));
        Map<String, Integer> countsBySource = new java.util.LinkedHashMap<>();
        service.discoverWithLanes(
                "ziskovost ceskych bank",
                "banking_finance",
                false,
                (source, lane) -> countsBySource.put(source, (Integer) lane.get("count")));

        assertEquals(2, countsBySource.get("arad"));
        assertEquals(1, countsBySource.get("fred"));
        verify(deepSearch, times(0)).deepSearchWithLanes(anyMap(), any());
    }

    @Test
    void discoverWithLanesReplaysActualLaneCountsInsteadOfInferringThemFromTopRows() {
        CatalogDeepSearchService deepSearch = mock(CatalogDeepSearchService.class);
        ExploreDiscoveryCache cache = mock(ExploreDiscoveryCache.class);
        List<Map<String, Object>> cachedTopRows = List.of(hit("eurostat", "EUROSTAT_1", "Top indicator"));
        Map<String, Integer> actualLaneCounts = Map.of("arad", 7, "eurostat", 2, "fred", 0);
        when(cache.buildKey(any(), any(), anyBoolean())).thenReturn("some-key");
        when(cache.get("some-key"))
                .thenReturn(Optional.of(new ExploreDiscoveryCache.CachedEntry(
                        cachedTopRows, List.of(), 1, 0L, 125L, actualLaneCounts)));

        ExploreDiscoveryService service =
                new ExploreDiscoveryService(deepSearch, cache, canonicalMetadataService(), v1Flags(), mock(SearchV2Service.class));
        Map<String, Integer> replayed = new java.util.LinkedHashMap<>();
        service.discoverWithLanes(
                "ziskovost ceskych bank",
                "banking_finance",
                false,
                (source, lane) -> replayed.put(source, (Integer) lane.get("count")));

        assertEquals(actualLaneCounts, replayed);
        verify(deepSearch, times(0)).deepSearchWithLanes(anyMap(), any());
    }
}
