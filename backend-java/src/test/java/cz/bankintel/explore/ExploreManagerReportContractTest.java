package cz.bankintel.explore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExploreManagerReportContractTest {

    @Test
    void structuredSectorMetadataWinsOverMacroSourceFallback() {
        String bucket = ExploreSectionBucketService.fetchItemBucket(Map.of(
                "source_type", "eurostat",
                "manager_category", "sector_indicators"));

        assertEquals("sector", bucket);
    }

    @Test
    void chartPayloadUsesFrontendChartContractAndStableIdentity() {
        Map<String, Object> loaded = Map.of(
                "title", "Return on assets",
                "source_type", "ecb2",
                "set_id", "dataset/roa",
                "series_id", "series-roa",
                "chart_points", List.of(Map.of("x", "2024", "y", 1.2), Map.of("x", "2025", "y", 1.4)));

        Map<String, Object> payload = ExploreSummarizeFetchService.buildChartPayload(List.of(loaded));
        List<?> series = (List<?>) payload.get("series");
        Map<?, ?> chart = (Map<?, ?>) series.get(0);

        assertEquals("Return on assets", chart.get("name"));
        assertEquals("series-roa", chart.get("series_id"));
        assertTrue(chart.get("data") instanceof List<?>);
    }

    @Test
    void coveragePreservesSourceDatasetAndSeriesIdentity() {
        Map<String, Object> loaded = Map.of(
                "title", "Return on assets",
                "source_type", "ecb2",
                "set_id", "dataset/roa",
                "dataset_id", "dataset/roa",
                "series_id", "series-roa");

        Map<String, Object> coverage = ExploreSummarizeFetchService
                .buildSeriesCoverage(List.of(loaded), List.of())
                .get(0);

        assertEquals("ecb2", coverage.get("source_type"));
        assertEquals("dataset/roa", coverage.get("dataset_id"));
        assertEquals("series-roa", coverage.get("series_id"));
    }
}
