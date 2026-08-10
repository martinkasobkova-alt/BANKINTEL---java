package cz.bankintel.explore.manager.refresh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagerSegmentBundleLoaderTest {

    @TempDir
    Path tempDir;

    private ManagerSegmentBundleLoader loader;

    @BeforeEach
    void setUp() {
        System.setProperty("MANAGER_SEGMENT_BUNDLES_DIR", tempDir.toString());
        loader = new ManagerSegmentBundleLoader(new ObjectMapper());
    }

    @AfterEach
    void cleanup() {
        System.clearProperty("MANAGER_SEGMENT_BUNDLES_DIR");
    }

    private void writeBundle(String fileName, String json) throws IOException {
        Files.writeString(tempDir.resolve(fileName), json);
    }

    @Test
    void filtersToEurostatRowsAndStampsSegmentId() throws IOException {
        writeBundle(
                "manufacturing_general.json",
                """
                {
                  "segment_id": "manufacturing_general",
                  "series": [
                    {"series_id": "sts_inpr_m_manufacturing_total", "source": "eurostat", "dataset_id": "sts_inpr_m",
                     "status": "active", "query_params": {"geo": "EU27_2020"}, "geo_coverage": ["IT", "DE", "CZ"]},
                    {"series_id": "csu_prod_1", "source": "csu", "dataset_id": "PRU01CT1", "status": "active"}
                  ]
                }
                """);

        List<Map<String, Object>> rows = loader.eurostatRows();

        assertEquals(1, rows.size());
        assertEquals("sts_inpr_m_manufacturing_total", rows.get(0).get("series_id"));
        assertEquals("manufacturing_general", rows.get(0).get("segment_id"));
    }

    @Test
    void excludesInactiveStatusButKeepsBlankAndVerified() throws IOException {
        writeBundle(
                "mixed_status.json",
                """
                {
                  "segment_id": "mixed_status",
                  "series": [
                    {"series_id": "a", "source": "eurostat", "dataset_id": "d1", "status": "inactive"},
                    {"series_id": "b", "source": "eurostat", "dataset_id": "d2", "status": "verified"},
                    {"series_id": "c", "source": "eurostat", "dataset_id": "d3"}
                  ]
                }
                """);

        List<String> ids = loader.eurostatRows().stream().map(r -> String.valueOf(r.get("series_id"))).toList();

        assertEquals(List.of("b", "c"), ids);
    }

    @Test
    void loadsAcrossMultipleFilesAndCachesResult() throws IOException {
        writeBundle(
                "a.json",
                """
                {"segment_id": "a", "series": [{"series_id": "a1", "source": "eurostat", "dataset_id": "d1"}]}
                """);
        writeBundle(
                "b.json",
                """
                {"segment_id": "b", "series": [{"series_id": "b1", "source": "eurostat", "dataset_id": "d2"}]}
                """);

        List<Map<String, Object>> first = loader.eurostatRows();
        assertEquals(2, first.size());

        // Cached: adding a new file after the first call must not change the already-cached result.
        writeBundle(
                "c.json",
                """
                {"segment_id": "c", "series": [{"series_id": "c1", "source": "eurostat", "dataset_id": "d3"}]}
                """);
        assertEquals(2, loader.eurostatRows().size());
    }

    @Test
    void eurostatRowsForSegmentsFiltersBySegmentId() throws IOException {
        writeBundle(
                "seg1.json",
                """
                {"segment_id": "seg1", "series": [{"series_id": "s1", "source": "eurostat", "dataset_id": "d1"}]}
                """);
        writeBundle(
                "seg2.json",
                """
                {"segment_id": "seg2", "series": [{"series_id": "s2", "source": "eurostat", "dataset_id": "d2"}]}
                """);

        List<Map<String, Object>> filtered = loader.eurostatRowsForSegments(List.of("seg1"));

        assertEquals(1, filtered.size());
        assertEquals("s1", filtered.get(0).get("series_id"));
    }

    @Test
    void returnsEmptyWhenDirectoryMissing() {
        System.setProperty("MANAGER_SEGMENT_BUNDLES_DIR", tempDir.resolve("does-not-exist").toString());
        ManagerSegmentBundleLoader freshLoader = new ManagerSegmentBundleLoader(new ObjectMapper());

        assertTrue(freshLoader.eurostatRows().isEmpty());
    }
}
