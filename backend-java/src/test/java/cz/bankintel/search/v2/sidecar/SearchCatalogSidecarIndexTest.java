package cz.bankintel.search.v2.sidecar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.CatalogSearchProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchCatalogSidecarIndexTest {

    @TempDir
    Path tempDir;

    @Test
    void ecbMetadataAliasesWithoutPreviewIdentityAreNotIndexedAsSeries() throws Exception {
        Path indexDir = Files.createDirectories(tempDir.resolve("metadata-identity-index"));
        Path metadataDir = Files.createDirectories(tempDir.resolve("metadata-identity-metadata"));
        Path sidecarDir = Files.createDirectories(tempDir.resolve("metadata-identity-sidecar"));
        Path metadataFile = metadataDir.resolve("ecb2.jsonl");
        Files.writeString(
                metadataFile,
                "{\"source\":\"ecb2\",\"series_id\":\"ico_gross_written_premiums\","
                        + "\"title_original\":\"Gross written premiums\"}\n"
                        + "{\"source\":\"ecb2\",\"series_id\":\"SSI/A.SK.1251.T10.1.U6.Z01.E\","
                        + "\"title_original\":\"Insurance corporations · SK · Total assets\"}\n");

        CatalogSearchProperties properties = mock(CatalogSearchProperties.class);
        when(properties.indexDir()).thenReturn(indexDir);
        when(properties.metadataDir()).thenReturn(metadataDir);
        when(properties.sidecarDir()).thenReturn(sidecarDir);
        when(properties.sidecarFtsDbPath()).thenReturn(sidecarDir.resolve("sidecar.sqlite"));
        when(properties.jsonlPath("ecb2")).thenReturn(indexDir.resolve("missing.jsonl"));
        when(properties.metadataPath("ecb2")).thenReturn(metadataFile);

        ObjectMapper objectMapper = new ObjectMapper();
        SearchCatalogSidecarBuilder builder = new SearchCatalogSidecarBuilder(objectMapper);
        builder.loadTaxonomy();
        SearchCatalogSidecarIndex index = new SearchCatalogSidecarIndex(properties, objectMapper, builder);

        assertThat(index.rebuild(List.of("ecb2"))).containsEntry("ok", true);
        assertThat(index.search("ecb2", "gross written premiums", 5))
                .extracting(row -> row.get("series_id"))
                .doesNotContain("ico_gross_written_premiums");
        assertThat(index.search("ecb2", "insurance corporations slovakia total assets", 5))
                .extracting(row -> row.get("series_id"))
                .contains("SSI/A.SK.1251.T10.1.U6.Z01.E");
    }

    @Test
    void unchangedSourceIsSkippedAndChangedSourceIsMergedAndPruned() throws Exception {
        Path indexDir = Files.createDirectories(tempDir.resolve("incremental-index"));
        Path metadataDir = Files.createDirectories(tempDir.resolve("incremental-metadata"));
        Path sidecarDir = Files.createDirectories(tempDir.resolve("incremental-sidecar"));
        Path sourceFile = indexDir.resolve("ecb2.jsonl");
        Files.writeString(sourceFile,
                "{\"source\":\"ecb2\",\"set_id\":\"CURRENT\",\"title\":\"Current banking income\"}\n"
                        + "{\"source\":\"ecb2\",\"set_id\":\"REMOVED\",\"title\":\"Old banking income\"}\n");

        CatalogSearchProperties properties = mock(CatalogSearchProperties.class);
        when(properties.indexDir()).thenReturn(indexDir);
        when(properties.metadataDir()).thenReturn(metadataDir);
        when(properties.sidecarDir()).thenReturn(sidecarDir);
        when(properties.sidecarFtsDbPath()).thenReturn(sidecarDir.resolve("sidecar.sqlite"));
        when(properties.jsonlPath("ecb2")).thenReturn(sourceFile);
        when(properties.metadataPath("ecb2")).thenReturn(metadataDir.resolve("ecb2.jsonl"));

        ObjectMapper objectMapper = new ObjectMapper();
        SearchCatalogSidecarBuilder builder = new SearchCatalogSidecarBuilder(objectMapper);
        builder.loadTaxonomy();
        SearchCatalogSidecarIndex index = new SearchCatalogSidecarIndex(properties, objectMapper, builder);

        Map<String, Object> first = index.rebuild(List.of("ecb2"));
        assertThat(first).containsEntry("changed_document_count", 2);
        long firstRevision = index.contentRevision();

        Map<String, Object> second = index.rebuild(List.of("ecb2"));
        assertThat(second).containsEntry("changed_document_count", 0);
        assertThat(index.contentRevision()).isEqualTo(firstRevision);
        @SuppressWarnings("unchecked")
        Map<String, Object> skipped = ((List<Map<String, Object>>) second.get("sources")).getFirst();
        assertThat(skipped).containsEntry("skipped_unchanged_source", true);

        Files.writeString(sourceFile,
                "{\"source\":\"ecb2\",\"set_id\":\"CURRENT\",\"title\":\"Current net banking income\"}\n");
        Map<String, Object> third = index.rebuild(List.of("ecb2"));
        assertThat(third).containsEntry("changed_document_count", 2);
        assertThat(index.search("ecb2", "current net banking income", 5))
                .extracting(row -> row.get("series_id"))
                .containsExactly("CURRENT");
        assertThat(index.search("ecb2", "old banking income", 5))
                .extracting(row -> row.get("series_id"))
                .doesNotContain("REMOVED");
    }

    @Test
    void lifecycleUsesStructuredStatusAndCoverageInsteadOfSeriesNames() {
        assertThat(SearchSeriesLifecycleClassifier.classify("discontinued", "", "Q", 2026).status())
                .isEqualTo("historical");
        assertThat(SearchSeriesLifecycleClassifier.classify("current", "", "Q", 2026).status())
                .isEqualTo("current");
        assertThat(SearchSeriesLifecycleClassifier.classify("", "2013-Q4", "Q", 2026).status())
                .isEqualTo("historical");
        assertThat(SearchSeriesLifecycleClassifier.classify("", "2025", "A", 2026).status())
                .isEqualTo("current");
        assertThat(SearchSeriesLifecycleClassifier.classify("ecb2", "CBD", "", "", "A", 2026).status())
                .isEqualTo("historical");
        assertThat(SearchSeriesLifecycleClassifier.classify("ecb2", "CBD2", "", "", "A", 2026).status())
                .isEqualTo("current");
        assertThat(SearchSeriesLifecycleClassifier.classifyDatasetSeries("ecb2", "CBD2", "current", "", "A", 2026).status())
                .isEqualTo("unknown");
        assertThat(SearchSeriesLifecycleClassifier.classifyDatasetSeries("ecb2", "CBD2", "current", "2017", "A", 2026).status())
                .isEqualTo("historical");
    }

    @Test
    void coverageReportsCurrentHistoricalAndLowConfidenceLifecycleRows() throws Exception {
        Path indexDir = Files.createDirectories(tempDir.resolve("lifecycle-index"));
        Path metadataDir = Files.createDirectories(tempDir.resolve("lifecycle-metadata"));
        Path sidecarDir = Files.createDirectories(tempDir.resolve("lifecycle-sidecar"));
        Path sourceFile = indexDir.resolve("ecb2.jsonl");
        Files.writeString(sourceFile,
                "{\"source\":\"ecb2\",\"set_id\":\"CURRENT\",\"title\":\"Current series\",\"dataset_lifecycle\":\"current\"}\n"
                        + "{\"source\":\"ecb2\",\"set_id\":\"OLD\",\"title\":\"Archived series\",\"dataset_lifecycle\":\"discontinued\"}\n"
                        + "{\"source\":\"ecb2\",\"set_id\":\"UNKNOWN\",\"title\":\"Unclassified series\"}\n");

        CatalogSearchProperties properties = mock(CatalogSearchProperties.class);
        when(properties.indexDir()).thenReturn(indexDir);
        when(properties.metadataDir()).thenReturn(metadataDir);
        when(properties.sidecarDir()).thenReturn(sidecarDir);
        when(properties.sidecarFtsDbPath()).thenReturn(sidecarDir.resolve("sidecar.sqlite"));
        when(properties.jsonlPath("ecb2")).thenReturn(sourceFile);
        when(properties.metadataPath("ecb2")).thenReturn(metadataDir.resolve("ecb2.jsonl"));

        ObjectMapper objectMapper = new ObjectMapper();
        SearchCatalogSidecarBuilder builder = new SearchCatalogSidecarBuilder(objectMapper);
        builder.loadTaxonomy();
        SearchCatalogSidecarIndex index = new SearchCatalogSidecarIndex(properties, objectMapper, builder);
        index.rebuild(List.of("ecb2"));

        Map<String, Object> coverage = awaitCoverage(index);
        @SuppressWarnings("unchecked")
        Map<String, Object> ecbCoverage = ((List<Map<String, Object>>) coverage.get("sources"))
                .stream()
                .filter(source -> "ecb2".equals(source.get("source")))
                .findFirst()
                .orElseThrow();
        assertThat(ecbCoverage)
                .containsEntry("current_rows", 0)
                .containsEntry("historical_rows", 1)
                .containsEntry("unknown_lifecycle_rows", 2)
                .containsEntry("inferred_lifecycle_rows", 2);
        assertThat(index.coverage()).containsEntry("status", "ready");
    }

    private static Map<String, Object> awaitCoverage(SearchCatalogSidecarIndex index) throws Exception {
        Map<String, Object> coverage = index.coverage();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!"ready".equals(coverage.get("status")) && System.nanoTime() < deadline) {
            Thread.sleep(10);
            coverage = index.coverage();
        }
        return coverage;
    }

    @Test
    void partialRebuildPreservesDocumentsFromOtherSources() throws Exception {
        Path indexDir = Files.createDirectories(tempDir.resolve("index"));
        Path metadataDir = Files.createDirectories(tempDir.resolve("metadata"));
        Path sidecarDir = Files.createDirectories(tempDir.resolve("sidecar"));
        Files.writeString(indexDir.resolve("arad.jsonl"),
                "{\"source\":\"arad\",\"set_id\":\"ARAD_RATE\",\"title\":\"Policy rate\"}\n");
        Files.writeString(indexDir.resolve("bis.jsonl"),
                "{\"source\":\"bis\",\"set_id\":\"BIS_ROA\",\"title\":\"Return on assets\"}\n");

        CatalogSearchProperties properties = mock(CatalogSearchProperties.class);
        when(properties.indexDir()).thenReturn(indexDir);
        when(properties.metadataDir()).thenReturn(metadataDir);
        when(properties.sidecarDir()).thenReturn(sidecarDir);
        when(properties.sidecarFtsDbPath()).thenReturn(sidecarDir.resolve("sidecar.sqlite"));
        when(properties.jsonlPath("arad")).thenReturn(indexDir.resolve("arad.jsonl"));
        when(properties.jsonlPath("bis")).thenReturn(indexDir.resolve("bis.jsonl"));
        when(properties.metadataPath("arad")).thenReturn(metadataDir.resolve("arad.jsonl"));
        when(properties.metadataPath("bis")).thenReturn(metadataDir.resolve("bis.jsonl"));

        ObjectMapper objectMapper = new ObjectMapper();
        SearchCatalogSidecarBuilder builder = new SearchCatalogSidecarBuilder(objectMapper);
        builder.loadTaxonomy();
        SearchCatalogSidecarIndex index = new SearchCatalogSidecarIndex(properties, objectMapper, builder);

        assertThat(index.contentRevision()).isZero();
        assertThat(index.rebuild(List.of("arad"))).containsEntry("ok", true);
        assertThat(index.contentRevision()).isEqualTo(1);
        assertThat(index.rebuild(List.of("bis"))).containsEntry("ok", true);
        assertThat(index.contentRevision()).isEqualTo(2);

        assertThat(index.search("arad", "policy rate", 5))
                .extracting(row -> row.get("series_id"))
                .containsExactly("ARAD_RATE");
        assertThat(index.search("bis", "return assets", 5))
                .extracting(row -> row.get("series_id"))
                .containsExactly("BIS_ROA");
    }

    @Test
    void sharedSearchReservesRecallForSmallerCatalogs() throws Exception {
        Path indexDir = Files.createDirectories(tempDir.resolve("balanced-index"));
        Path metadataDir = Files.createDirectories(tempDir.resolve("balanced-metadata"));
        Path sidecarDir = Files.createDirectories(tempDir.resolve("balanced-sidecar"));
        StringBuilder largeCatalog = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            largeCatalog.append("{\"source\":\"fred\",\"set_id\":\"FRED_WAGE_")
                    .append(i)
                    .append("\",\"title\":\"Average wages Austria exact series ")
                    .append(i)
                    .append("\"}\n");
        }
        Files.writeString(indexDir.resolve("fred.jsonl"), largeCatalog);
        Files.writeString(indexDir.resolve("eurostat.jsonl"),
                "{\"source\":\"eurostat\",\"set_id\":\"nama_10_fte\","
                        + "\"title\":\"Average salary per employee\","
                        + "\"description\":\"Average wages in the total economy\"}\n");

        CatalogSearchProperties properties = mock(CatalogSearchProperties.class);
        when(properties.indexDir()).thenReturn(indexDir);
        when(properties.metadataDir()).thenReturn(metadataDir);
        when(properties.sidecarDir()).thenReturn(sidecarDir);
        when(properties.sidecarFtsDbPath()).thenReturn(sidecarDir.resolve("sidecar.sqlite"));
        for (String source : List.of("fred", "eurostat")) {
            when(properties.jsonlPath(source)).thenReturn(indexDir.resolve(source + ".jsonl"));
            when(properties.metadataPath(source)).thenReturn(metadataDir.resolve(source + ".jsonl"));
        }

        ObjectMapper objectMapper = new ObjectMapper();
        SearchCatalogSidecarBuilder builder = new SearchCatalogSidecarBuilder(objectMapper);
        builder.loadTaxonomy();
        SearchCatalogSidecarIndex index = new SearchCatalogSidecarIndex(properties, objectMapper, builder);
        assertThat(index.rebuild(List.of("fred", "eurostat"))).containsEntry("ok", true);

        List<Map<String, Object>> results = index.search(
                List.of("fred", "eurostat"), "average wages", 10);

        assertThat(results)
                .extracting(row -> row.get("source"))
                .contains("fred", "eurostat");
        assertThat(results)
                .anyMatch(row -> "eurostat".equals(row.get("source"))
                        && "nama_10_fte".equals(row.get("series_id")));
    }

    @Test
    void relaxedLaneExposesPartialMatchesToSemanticReranking() throws Exception {
        Path indexDir = Files.createDirectories(tempDir.resolve("hybrid-index"));
        Path metadataDir = Files.createDirectories(tempDir.resolve("hybrid-metadata"));
        Path sidecarDir = Files.createDirectories(tempDir.resolve("hybrid-sidecar"));
        Files.writeString(indexDir.resolve("eurostat.jsonl"),
                "{\"source\":\"eurostat\",\"set_id\":\"salary_series\","
                        + "\"title\":\"Average salary per employee\","
                        + "\"description\":\"Average wages in the total economy\"}\n"
                        + "{\"source\":\"eurostat\",\"set_id\":\"growth_series\","
                        + "\"title\":\"Economic growth rate\"}\n");

        CatalogSearchProperties properties = mock(CatalogSearchProperties.class);
        when(properties.indexDir()).thenReturn(indexDir);
        when(properties.metadataDir()).thenReturn(metadataDir);
        when(properties.sidecarDir()).thenReturn(sidecarDir);
        when(properties.sidecarFtsDbPath()).thenReturn(sidecarDir.resolve("sidecar.sqlite"));
        when(properties.jsonlPath("eurostat")).thenReturn(indexDir.resolve("eurostat.jsonl"));
        when(properties.metadataPath("eurostat")).thenReturn(metadataDir.resolve("eurostat.jsonl"));

        ObjectMapper objectMapper = new ObjectMapper();
        SearchCatalogSidecarBuilder builder = new SearchCatalogSidecarBuilder(objectMapper);
        builder.loadTaxonomy();
        SearchCatalogSidecarIndex index = new SearchCatalogSidecarIndex(properties, objectMapper, builder);
        assertThat(index.rebuild(List.of("eurostat"))).containsEntry("ok", true);

        List<Map<String, Object>> results = index.search("eurostat", "wage growth", 8);

        assertThat(results)
                .anyMatch(row -> "salary_series".equals(row.get("series_id"))
                        && "relaxed".equals(row.get("_retrieval_lane")));
    }

    @Test
    void relaxedLaneIsSkippedWhenStrictRecallIsAlreadySufficient() throws Exception {
        Path indexDir = Files.createDirectories(tempDir.resolve("strict-sufficient-index"));
        Path metadataDir = Files.createDirectories(tempDir.resolve("strict-sufficient-metadata"));
        Path sidecarDir = Files.createDirectories(tempDir.resolve("strict-sufficient-sidecar"));
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            rows.append("{\"source\":\"ecb2\",\"set_id\":\"inflation_")
                    .append(i)
                    .append("\",\"title\":\"Inflation rate series ")
                    .append(i)
                    .append("\"}\n");
        }
        rows.append("{\"source\":\"ecb2\",\"set_id\":\"partial\",\"title\":\"Inflation expectations\"}\n");
        Files.writeString(indexDir.resolve("ecb2.jsonl"), rows.toString());

        CatalogSearchProperties properties = mock(CatalogSearchProperties.class);
        when(properties.indexDir()).thenReturn(indexDir);
        when(properties.metadataDir()).thenReturn(metadataDir);
        when(properties.sidecarDir()).thenReturn(sidecarDir);
        when(properties.sidecarFtsDbPath()).thenReturn(sidecarDir.resolve("sidecar.sqlite"));
        when(properties.jsonlPath("ecb2")).thenReturn(indexDir.resolve("ecb2.jsonl"));
        when(properties.metadataPath("ecb2")).thenReturn(metadataDir.resolve("ecb2.jsonl"));

        ObjectMapper objectMapper = new ObjectMapper();
        SearchCatalogSidecarBuilder builder = new SearchCatalogSidecarBuilder(objectMapper);
        builder.loadTaxonomy();
        SearchCatalogSidecarIndex index = new SearchCatalogSidecarIndex(properties, objectMapper, builder);
        assertThat(index.rebuild(List.of("ecb2"))).containsEntry("ok", true);

        List<Map<String, Object>> results = index.search("ecb2", "inflation rate", 8);

        assertThat(results).hasSize(6);
        assertThat(results).allMatch(row -> "strict".equals(row.get("_retrieval_lane")));
    }

    @Test
    void relaxedMatchUsesBoundedPrefixOrQueries() {
        assertThat(SearchCatalogSidecarIndex.buildMatch("wage growth"))
                .isEqualTo("\"wage\" AND \"growth\"");
        assertThat(SearchCatalogSidecarIndex.buildRelaxedMatch("wage growth"))
                .isEqualTo("{canonical_title primary_concept aliases original_title category} : (\"wage\"* OR \"growth\"*)");
        assertThat(SearchCatalogSidecarIndex.buildRelaxedMatch("GDP")).isBlank();
    }

    @Test
    void parallelReadVariantsKeepReturningIndexedRows() throws Exception {
        Path indexDir = Files.createDirectories(tempDir.resolve("parallel-index"));
        Path metadataDir = Files.createDirectories(tempDir.resolve("parallel-metadata"));
        Path sidecarDir = Files.createDirectories(tempDir.resolve("parallel-sidecar"));
        Path sourceFile = indexDir.resolve("ecb2.jsonl");
        Files.writeString(sourceFile,
                "{\"source\":\"ecb2\",\"set_id\":\"CBD2/Q.SK.P2110\","
                        + "\"title\":\"Net interest income Slovak banks\","
                        + "\"description\":\"Current net interest income for banks in Slovakia\"}\n");

        CatalogSearchProperties properties = mock(CatalogSearchProperties.class);
        when(properties.indexDir()).thenReturn(indexDir);
        when(properties.metadataDir()).thenReturn(metadataDir);
        when(properties.sidecarDir()).thenReturn(sidecarDir);
        when(properties.sidecarFtsDbPath()).thenReturn(sidecarDir.resolve("sidecar.sqlite"));
        when(properties.jsonlPath("ecb2")).thenReturn(sourceFile);
        when(properties.metadataPath("ecb2")).thenReturn(metadataDir.resolve("ecb2.jsonl"));

        ObjectMapper objectMapper = new ObjectMapper();
        SearchCatalogSidecarBuilder builder = new SearchCatalogSidecarBuilder(objectMapper);
        builder.loadTaxonomy();
        SearchCatalogSidecarIndex index = new SearchCatalogSidecarIndex(properties, objectMapper, builder);
        assertThat(index.rebuild(List.of("ecb2"))).containsEntry("ok", true);

        List<String> variants = List.of(
                "net interest income",
                "interest income Slovak banks",
                "urokove vynosy bank",
                "net interest income SK");
        try (var executor = Executors.newFixedThreadPool(variants.size())) {
            List<CompletableFuture<List<Map<String, Object>>>> searches = variants.stream()
                    .map(query -> CompletableFuture.supplyAsync(() -> index.search("ecb2", query, 10), executor))
                    .toList();
            CompletableFuture.allOf(searches.toArray(CompletableFuture[]::new)).join();
            assertThat(searches)
                    .allSatisfy(search -> assertThat(search.join())
                            .extracting(row -> row.get("series_id"))
                            .contains("CBD2/Q.SK.P2110"));
        }
    }
}
