package cz.bankintel.explore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ETAPA 7: {@code series_used} and {@code series_coverage} were never populated by either
 * summarize flow ({@link ExploreSummarizeService}, {@link ExploreInstantThenDetailService}) - the
 * frontend's "Zobrazit použité datové řady" details block and the "Všechny řady ve zpracování"
 * section (which requires {@code series_coverage} to be an ARRAY, not the {loaded, failed,
 * requested} count object both flows used to send) were therefore permanently empty. These tests
 * cover the new {@link ExploreSummarizeFetchService#buildSeriesUsed} /
 * {@link ExploreSummarizeFetchService#buildSeriesCoverage} helpers directly.
 */
class ExploreSummarizeFetchServiceSeriesUsedTest {

    private static Map<String, Object> loadedRow(String title, String setId, String contextLine) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("title", title);
        row.put("set_id", setId);
        row.put("data_context_line", contextLine);
        return row;
    }

    private static Map<String, Object> failedRow(String title, String setId, String reason) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("title", title);
        row.put("set_id", setId);
        row.put("reason", reason);
        return row;
    }

    @Test
    void buildSeriesUsedReturnsFlatTitleStringsForFrontendConsumption() {
        List<Map<String, Object>> loaded = List.of(
                loadedRow("Zisk bank ČR", "1012:SBBAM02911", "..."),
                loadedRow("HDP Německo", "nama_10_gdp", "..."));

        List<String> seriesUsed = ExploreSummarizeFetchService.buildSeriesUsed(loaded);

        assertEquals(List.of("Zisk bank ČR", "HDP Německo"), seriesUsed);
    }

    @Test
    void buildSeriesUsedSkipsRowsWithoutATitle() {
        List<Map<String, Object>> loaded = List.of(loadedRow("", "some_id", "..."), loadedRow("Real title", "id2", "..."));

        List<String> seriesUsed = ExploreSummarizeFetchService.buildSeriesUsed(loaded);

        assertEquals(List.of("Real title"), seriesUsed);
    }

    @Test
    void buildSeriesCoverageIsAnArrayWithLoadedAndFailedRowsCorrectlyStatused() {
        List<Map<String, Object>> loaded = List.of(loadedRow("Zisk bank ČR", "1012:SBBAM02911", "poslední hodnota 69,96 mld."));
        List<Map<String, Object>> failed = List.of(failedRow("ROE pojišťoven ES", "roe_es", "fetch_failed_or_insufficient_points"));

        List<Map<String, Object>> coverage = ExploreSummarizeFetchService.buildSeriesCoverage(loaded, failed);

        assertEquals(2, coverage.size());
        Map<String, Object> loadedCov = coverage.get(0);
        assertEquals("Zisk bank ČR", loadedCov.get("title"));
        assertEquals("1012:SBBAM02911", loadedCov.get("series_id"));
        assertEquals("loaded", loadedCov.get("status"));
        assertEquals("poslední hodnota 69,96 mld.", loadedCov.get("fact"));

        Map<String, Object> failedCov = coverage.get(1);
        assertEquals("ROE pojišťoven ES", failedCov.get("title"));
        assertEquals("failed", failedCov.get("status"));
        assertTrue(String.valueOf(failedCov.get("reason")).length() > 0, "failed rows must carry a human-readable reason");
    }

    @Test
    void buildSeriesCoverageOfEmptyInputsIsAnEmptyArrayNotNull() {
        List<Map<String, Object>> coverage = ExploreSummarizeFetchService.buildSeriesCoverage(List.of(), List.of());
        assertTrue(coverage.isEmpty());
    }
}
