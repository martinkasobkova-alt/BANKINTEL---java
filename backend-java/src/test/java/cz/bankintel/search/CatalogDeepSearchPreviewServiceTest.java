package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cz.bankintel.connector.ConnectorFactory;
import cz.bankintel.search.model.CatalogKeys;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class CatalogDeepSearchPreviewServiceTest {

    @Test
    void previewCacheBypassIsRestrictedToColdPathProfile() {
        MockEnvironment regularEnvironment = new MockEnvironment();
        regularEnvironment.setActiveProfiles("local");
        CatalogDeepSearchPreviewService regular = new CatalogDeepSearchPreviewService(
                mock(CatalogPreviewOrchestrator.class), mock(ConnectorFactory.class), regularEnvironment);
        MockEnvironment coldEnvironment = new MockEnvironment();
        coldEnvironment.setActiveProfiles("local", "cold-path-profile");
        CatalogDeepSearchPreviewService cold = new CatalogDeepSearchPreviewService(
                mock(CatalogPreviewOrchestrator.class), mock(ConnectorFactory.class), coldEnvironment);
        try {
            assertFalse(regular.previewOutcomeCacheBypassedForColdPathProfile());
            assertTrue(cold.previewOutcomeCacheBypassedForColdPathProfile());
        } finally {
            regular.shutdownPreviewExecutor();
            cold.shutdownPreviewExecutor();
        }
    }

    @Test
    void balancedPreviewPoolMixesSources() {
        List<Map<String, Object>> fetchable = List.of(
                row("imf", "a", 100),
                row("imf", "b", 95),
                row("imf", "c", 90),
                row("imf", "d", 85),
                row("commodities", "oil", 80),
                row("fred", "brent", 75));

        List<Map<String, Object>> pool = CatalogDeepSearchPreviewService.selectBalancedPreviewPool(fetchable, 4);

        assertEquals(4, pool.size());
        long imfCount = pool.stream().filter(r -> "imf".equals(r.get(CatalogKeys.SOURCE_TYPE))).count();
        long otherCount = pool.size() - imfCount;
        assertTrue(otherCount >= 2, "pool should include non-imf sources: " + pool);
    }

    @Test
    void managerPreviewPoolPinsCoreGdpEvenWhenLowRanked() {
        List<Map<String, Object>> fetchable = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            fetchable.add(row("eurostat", "prc_hicp_" + i, "HICP series " + i, 900 - i));
        }
        fetchable.add(row(
                "eurostat",
                "nama_10_gdp",
                "Gross domestic product (GDP) and main components",
                10));
        fetchable.add(row(
                "fred",
                "ECBMRRFR",
                "ECB Main Refinancing Operations Rate: Fixed Rate Tenders for Euro Area",
                5));
        for (int i = 0; i < 10; i++) {
            fetchable.add(row("imf", "imf_" + i, 800 - i));
        }

        List<Map<String, Object>> pool =
                CatalogDeepSearchPreviewService.selectBalancedPreviewPool(fetchable, 8, true);

        assertTrue(
                pool.stream().anyMatch(r -> "nama_10_gdp".equals(r.get(CatalogKeys.SET_ID))),
                "core GDP must be pinned into Manager preview pool: " + pool);
        assertTrue(
                pool.stream().anyMatch(r -> "ECBMRRFR".equals(r.get(CatalogKeys.SET_ID))),
                "core policy rate must be pinned into Manager preview pool: " + pool);
    }

    @Test
    void previewPoolDeduplicatesCanonicalSourceAndSeriesIdentity() {
        Map<String, Object> preferred = row("ecb2", "MIR/M.SK.sample", 100);
        Map<String, Object> duplicate = row("ECB2", "MIR/M.SK.sample", 90);
        Map<String, Object> distinct = row("ecb2", "MIR/M.SK.other", 80);

        List<Map<String, Object>> unique =
                CatalogDeepSearchPreviewService.dedupePreviewIdentities(List.of(preferred, duplicate, distinct));

        assertEquals(List.of(preferred, distinct), unique);
    }

    @Test
    void previewSelectionPrioritizesSemanticFitBeforeLiveButWrongRows() {
        String query = "inflace spanelsko";
        Map<String, Object> geo = CatalogGeoIntent.detectGeoIntent(query);
        CatalogQueryRelevanceProfile profile = CatalogQueryRelevanceProfile.from(query, geo);
        Map<String, Object> hicp = row(
                "eurostat",
                "prc_hicp_midx",
                "HICP - monthly data (index) (1996-2025)",
                278);
        Map<String, Object> exchangeRate = row(
                "ecb2",
                "EXR/A.E01.AUD.ERC0.A",
                "Real effective exch. rate CPI deflated - Australian dollar",
                1_200);
        Map<String, Object> administeredPrices = row(
                "eurostat",
                "prc_hicp_apc",
                "HICP - administered prices (composition) (2001-2025)",
                278);

        assertTrue(
                CatalogDeepSearchPreviewService.previewSelectionScore(hicp, query, geo, profile)
                        > CatalogDeepSearchPreviewService.previewSelectionScore(exchangeRate, query, geo, profile),
                "semantic preview priority should not let CPI-deflated exchange rates consume HICP preview slots");
        assertTrue(
                CatalogDeepSearchPreviewService.previewSelectionScore(hicp, query, geo, profile)
                        > CatalogDeepSearchPreviewService.previewSelectionScore(administeredPrices, query, geo, profile),
                "generic inflation should prefer headline/all-items/index over unrequested specialized variants");
        assertTrue(CatalogDeepSearchPreviewService.countsTowardVerifiedTarget(hicp, query, geo), hicp.toString());
        assertTrue(
                !CatalogDeepSearchPreviewService.countsTowardVerifiedTarget(exchangeRate, query, geo),
                exchangeRate.toString());
    }

    @Test
    void buildPreviewPayloadKeepsDeeplyNestedQueryParams() {
        Map<String, Object> candidate = Map.of(
                CatalogKeys.SOURCE_TYPE,
                "oecd4",
                CatalogKeys.SET_ID,
                "housing_prices/CZE/RHP/_/A",
                "row",
                Map.of(
                        CatalogKeys.SET_ID,
                        "housing_prices/CZE/RHP/_/A",
                        "row",
                        Map.of(
                                CatalogKeys.SET_ID,
                                "housing_prices/CZE/RHP/_/A",
                                "query_params",
                                Map.of(
                                        "provider",
                                        "oecd4",
                                        "oecd_api_mode",
                                        "oecd4_offline",
                                        "oecd4_key",
                                        "housing_prices",
                                        "oecd4_ref_area",
                                        "CZE",
                                        "oecd4_measure",
                                        "RHP",
                                        "freq",
                                        "A"))));

        Map<String, Object> payload =
                CatalogDeepSearchPreviewService.buildPreviewPayload(candidate, "oecd", "real house prices");

        @SuppressWarnings("unchecked")
        Map<String, Object> qp = (Map<String, Object>) payload.get("query_params");
        assertEquals("oecd4", qp.get("provider"));
        assertEquals("housing_prices", qp.get("oecd4_key"));
        assertEquals("RHP", qp.get("oecd4_measure"));
    }

    @Test
    void previewTelemetryIsCompleteAndDoesNotLeakInternalKeys() {
        ConnectorFactory connectorFactory = mock(ConnectorFactory.class);
        when(connectorFactory.isSupported("unknown-source")).thenReturn(false);
        CatalogDeepSearchPreviewService service = new CatalogDeepSearchPreviewService(
                mock(CatalogPreviewOrchestrator.class), connectorFactory, new MockEnvironment());
        try {
            CatalogDeepSearchPreviewService.PreviewPhaseResult result = service.verifyCandidates(
                    List.of(Map.of(
                            CatalogKeys.SOURCE_TYPE, "unknown-source",
                            CatalogKeys.SET_ID, "sample",
                            "title", "Sample series")),
                    "sample",
                    null);

            assertTrue(result.previewTiming().containsKey("preview_queue_wait_ms"));
            assertTrue(result.previewTiming().containsKey("preview_fetch_ms"));
            assertTrue(result.previewTiming().containsKey("preview_parse_ms"));
            assertTrue(result.previewTiming().containsKey("preview_validation_ms"));
            assertTrue(result.previewTiming().containsKey("preview_items"));
            assertTrue(result.previewTiming().containsKey("preview_by_source"));
            assertEquals(1, result.possible().size());
            assertTrue(result.possible().get(0).keySet().stream().noneMatch(key -> key.startsWith("_telemetry_")));
        } finally {
            service.shutdownPreviewExecutor();
        }
    }

    @Test
    void managerPreviewPoolPinsIntentSeeds() {
        List<Map<String, Object>> fetchable = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            fetchable.add(row("eurostat", "noise_" + i, "Noise series " + i, 900 - i));
        }
        Map<String, Object> intentSeed = new java.util.LinkedHashMap<>(row(
                "eurostat",
                "sts_inpr_m",
                "Production in industry - manufacturing",
                5));
        intentSeed.put("manager_intent_seed", true);
        fetchable.add(intentSeed);

        List<Map<String, Object>> pool =
                CatalogDeepSearchPreviewService.selectBalancedPreviewPool(fetchable, 8, true);

        assertTrue(
                pool.stream().anyMatch(r -> "sts_inpr_m".equals(r.get(CatalogKeys.SET_ID))),
                "intent preview seed must be pinned into Manager preview pool: " + pool);
    }

    @Test
    void resilientPreviewPayloadForcesGeoAndNarrowTime() {
        Map<String, Object> row = row(
                "eurostat",
                "sts_inpr_m_manufacturing_total",
                "Production in industry",
                100);
        row = new java.util.LinkedHashMap<>(row);
        row.put("dataset", "sts_inpr_m");
        Map<String, Object> failed = Map.of(
                "preview_state", "sync_failed",
                "http_status", 413,
                "message", "Payload too large");

        Map<String, Object> retry = CatalogDeepSearchPreviewService.buildResilientPreviewPayload(
                row, "eurostat", "vyroba v rakousku", failed);

        assertTrue(retry != null, "retry payload expected");
        assertEquals("sts_inpr_m", retry.get("set_id"));
        assertEquals("AT", String.valueOf(retry.get("country")).toUpperCase());
        @SuppressWarnings("unchecked")
        Map<String, Object> qp = (Map<String, Object>) retry.get("query_params");
        assertEquals("AT", String.valueOf(qp.get("geo")).toUpperCase());
        assertEquals("1", String.valueOf(qp.get("lastTimePeriod")));
    }

    @Test
    void resilientPreviewPayloadForNonEurostatConnectorNeverOverridesCountry() {
        // Confirmed live: retrying a failed IMF preview for Panama's own series
        // (IMF|IMF.RES|WEO|9.0.0|PAN.LUR) forced country=DE (the query's own detected geo, from
        // "nezaměstnanost v Německu") into the refetch - returning Germany's unemployment data
        // mislabeled under the Panama candidate's own title. The geo override is Eurostat-only by
        // design (see javadoc); IMF/ECB/other connectors must keep their own row's country intact
        // and only get the safe lastTimePeriod narrowing.
        Map<String, Object> row = new java.util.LinkedHashMap<>(
                row("imf", "IMF|IMF.RES|WEO|9.0.0|PAN.LUR", "Míra nezaměstnanosti Panamy", 12));
        Map<String, Object> failed = Map.of("preview_state", "sync_failed", "message", "empty");

        Map<String, Object> retry = CatalogDeepSearchPreviewService.buildResilientPreviewPayload(
                row, "imf", "Jak se vyvíjí nezaměstnanost v Německu?", failed);

        assertFalse(
                retry != null && retry.containsKey("country"),
                "non-eurostat connector must never have its country overridden by the query's own geo: " + retry);
        if (retry != null && retry.get("query_params") instanceof Map<?, ?> qp) {
            assertFalse(qp.containsKey("geo"), "non-eurostat query_params must not gain a forced geo override: " + qp);
        }
    }

    private static Map<String, Object> row(String source, String setId, int score) {
        return Map.of(CatalogKeys.SOURCE_TYPE, source, CatalogKeys.SET_ID, setId, CatalogKeys.SEARCH_SCORE, score);
    }

    private static Map<String, Object> row(String source, String setId, String title, int score) {
        return Map.of(
                CatalogKeys.SOURCE_TYPE,
                source,
                CatalogKeys.SET_ID,
                setId,
                "title",
                title,
                CatalogKeys.SEARCH_SCORE,
                score);
    }
}
