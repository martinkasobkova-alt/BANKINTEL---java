package cz.bankintel.explore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cz.bankintel.connector.ConnectorFactory;
import cz.bankintel.explore.ExploreDtos.ExploreSummarizeSeriesItem;
import cz.bankintel.explore.manager.ManagerSeriesCacheReader;
import cz.bankintel.explore.manager.fetch.ManagerFetchRegistry;
import cz.bankintel.search.CatalogIndexStore;
import cz.bankintel.search.CatalogPreviewOrchestrator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ETAPA 5: {@link ExploreSummarizeFetchService#fetchBatch} used to fetch its (up to 14) selected
 * series one at a time - a slow or hanging connector for series #3 stalled every series after it,
 * and summarize latency scaled with the SUM of every series' fetch time. These tests exercise the
 * bounded-concurrency rewrite: order preservation, per-series failure isolation, and per-series
 * timeout/cancellation - using the package-private {@link
 * ExploreSummarizeFetchService#setFetchItemTimeoutSecForTest} seam so the timeout test does not
 * need a real 25s wait.
 */
class ExploreSummarizeFetchServiceParallelismTest {

    private static ExploreSummarizeSeriesItem item(String setId) {
        return new ExploreSummarizeSeriesItem(
                "arad", setId, "Title " + setId, Map.of(), true, null, false, null, null, null, null, null, null);
    }

    private static List<Map<String, Object>> observations(int n) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(Map.of("period", "2024-0" + (i + 1), "date", "2024-0" + (i + 1), "value", (double) i));
        }
        return out;
    }

    private static ExploreSummarizeFetchService newService(
            CatalogIndexStore indexStore,
            CatalogPreviewOrchestrator previewOrchestrator,
            ConnectorFactory connectorFactory,
            ManagerSeriesCacheReader cacheReader,
            ManagerFetchRegistry fetchRegistry) {
        when(indexStore.ftsDbAvailable()).thenReturn(true);
        when(indexStore.lookupRow(anyString(), anyString())).thenReturn(Optional.empty());
        when(fetchRegistry.tryFetch(any(), any())).thenReturn(Optional.empty());
        when(connectorFactory.isSupported(any())).thenReturn(false);
        return new ExploreSummarizeFetchService(
                indexStore,
                previewOrchestrator,
                connectorFactory,
                cacheReader,
                fetchRegistry,
                mock(cz.bankintel.repository.UserUploadRepository.class),
                mock(cz.bankintel.service.myseries.SavedSeriesResolverService.class));
    }

    @Test
    void oneSeriesFailingDoesNotPreventOthersFromLoadingAndOrderIsPreserved() {
        CatalogIndexStore indexStore = mock(CatalogIndexStore.class);
        ManagerSeriesCacheReader cacheReader = mock(ManagerSeriesCacheReader.class);
        ManagerFetchRegistry fetchRegistry = mock(ManagerFetchRegistry.class);
        ExploreSummarizeFetchService service = newService(
                indexStore, mock(CatalogPreviewOrchestrator.class), mock(ConnectorFactory.class), cacheReader, fetchRegistry);

        when(cacheReader.readObservations(argThat(m -> m != null && "ok_a".equals(m.get("set_id"))), any()))
                .thenReturn(Optional.of(observations(3)));
        when(cacheReader.readObservations(argThat(m -> m != null && "fails_b".equals(m.get("set_id"))), any()))
                .thenReturn(Optional.empty());
        when(cacheReader.readObservations(argThat(m -> m != null && "ok_c".equals(m.get("set_id"))), any()))
                .thenReturn(Optional.of(observations(4)));

        ExploreSummarizeFetchService.BatchResult result =
                service.fetchBatch(List.of(item("ok_a"), item("fails_b"), item("ok_c")), "CZ", 14);

        assertEquals(2, result.loaded().size());
        assertEquals(1, result.failed().size());
        assertEquals("ok_a", result.loaded().get(0).get("set_id"));
        assertEquals("ok_c", result.loaded().get(1).get("set_id"));
        assertEquals("fails_b", result.failed().get(0).get("set_id"));
    }

    @Test
    void seriesThatThrowsUnexpectedlyIsIsolatedAsAFailureNotABatchAbort() {
        CatalogIndexStore indexStore = mock(CatalogIndexStore.class);
        ManagerSeriesCacheReader cacheReader = mock(ManagerSeriesCacheReader.class);
        ManagerFetchRegistry fetchRegistry = mock(ManagerFetchRegistry.class);
        ExploreSummarizeFetchService service = newService(
                indexStore, mock(CatalogPreviewOrchestrator.class), mock(ConnectorFactory.class), cacheReader, fetchRegistry);

        when(cacheReader.readObservations(argThat(m -> m != null && "ok_a".equals(m.get("set_id"))), any()))
                .thenReturn(Optional.of(observations(3)));
        when(cacheReader.readObservations(argThat(m -> m != null && "throws_b".equals(m.get("set_id"))), any()))
                .thenThrow(new RuntimeException("boom - simulated Mongo failure"));
        when(cacheReader.readObservations(argThat(m -> m != null && "ok_c".equals(m.get("set_id"))), any()))
                .thenReturn(Optional.of(observations(4)));

        ExploreSummarizeFetchService.BatchResult result =
                service.fetchBatch(List.of(item("ok_a"), item("throws_b"), item("ok_c")), "CZ", 14);

        assertEquals(2, result.loaded().size());
        assertEquals(1, result.failed().size());
        assertEquals("throws_b", result.failed().get(0).get("set_id"));
    }

    @Test
    void sixSeriesFetchInParallelInsteadOfSequentially() {
        CatalogIndexStore indexStore = mock(CatalogIndexStore.class);
        ManagerSeriesCacheReader cacheReader = mock(ManagerSeriesCacheReader.class);
        ManagerFetchRegistry fetchRegistry = mock(ManagerFetchRegistry.class);
        ExploreSummarizeFetchService service = newService(
                indexStore, mock(CatalogPreviewOrchestrator.class), mock(ConnectorFactory.class), cacheReader, fetchRegistry);

        long perItemSleepMs = 300;
        when(cacheReader.readObservations(any(), any())).thenAnswer(inv -> {
            Thread.sleep(perItemSleepMs);
            return Optional.of(observations(3));
        });

        List<ExploreSummarizeSeriesItem> items =
                List.of(item("s1"), item("s2"), item("s3"), item("s4"), item("s5"), item("s6"));

        long start = System.currentTimeMillis();
        ExploreSummarizeFetchService.BatchResult result = service.fetchBatch(items, "CZ", 14);
        long elapsedMs = System.currentTimeMillis() - start;

        assertEquals(6, result.loaded().size());
        // Sequential would take ~6*300ms=1800ms; bounded-concurrency (6 fit in one wave) should
        // land close to a single item's latency. Generous ceiling to avoid CI flakiness while still
        // proving this is not the old one-at-a-time loop.
        assertTrue(elapsedMs < perItemSleepMs * 3, "expected parallel fan-out, took " + elapsedMs + "ms");
    }

    /**
     * ETAPA 5 before/after measurement for 1, 5, and 14 series. The OLD code was a plain
     * sequential for-loop with no concurrency at all, so its total cost for N series each taking
     * {@code perItemMs} is EXACTLY {@code N * perItemMs} - not an estimate, a direct consequence
     * of reading that loop (no fan-out, no early return once an item starts). The NEW
     * bounded-concurrency (6) wave-based fetch is measured live here, not assumed.
     */
    @Test
    void measuresLatencyScalingForOneFiveAndFourteenSeries() {
        long perItemMs = 800;
        long oldSequential1 = 1 * perItemMs;
        long oldSequential5 = 5 * perItemMs;
        long oldSequential14 = 14 * perItemMs;

        long new1 = measureFetchBatchMs(1, perItemMs);
        long new5 = measureFetchBatchMs(5, perItemMs);
        long new14 = measureFetchBatchMs(14, perItemMs);

        System.out.println("[ETAPA5 summarize latency] N=1  old(sequential,exact)=" + oldSequential1
                + "ms new(parallel,measured)=" + new1 + "ms");
        System.out.println("[ETAPA5 summarize latency] N=5  old(sequential,exact)=" + oldSequential5
                + "ms new(parallel,measured)=" + new5 + "ms");
        System.out.println("[ETAPA5 summarize latency] N=14 old(sequential,exact)=" + oldSequential14
                + "ms new(parallel,measured)=" + new14 + "ms");

        // N=1: no concurrency possible - must not regress (allow scheduling/mocking overhead).
        assertTrue(new1 < perItemMs * 2, "N=1 unexpectedly slow: " + new1 + "ms");
        // N=5: all 5 fit in a single wave of 6 -> close to one item's latency, nowhere near 5x.
        assertTrue(new5 < oldSequential5 / 2, "N=5 not parallelized: " + new5 + "ms vs sequential " + oldSequential5 + "ms");
        // N=14: ceil(14/6)=3 waves -> roughly 3x one item's latency, nowhere near 14x.
        assertTrue(new14 < oldSequential14 / 2, "N=14 not parallelized: " + new14 + "ms vs sequential " + oldSequential14 + "ms");
    }

    private static long measureFetchBatchMs(int seriesCount, long perItemMs) {
        CatalogIndexStore indexStore = mock(CatalogIndexStore.class);
        ManagerSeriesCacheReader cacheReader = mock(ManagerSeriesCacheReader.class);
        ManagerFetchRegistry fetchRegistry = mock(ManagerFetchRegistry.class);
        ExploreSummarizeFetchService service = newService(
                indexStore, mock(CatalogPreviewOrchestrator.class), mock(ConnectorFactory.class), cacheReader, fetchRegistry);
        when(cacheReader.readObservations(any(), any())).thenAnswer(inv -> {
            Thread.sleep(perItemMs);
            return Optional.of(observations(3));
        });
        List<ExploreSummarizeSeriesItem> items = new ArrayList<>();
        for (int i = 0; i < seriesCount; i++) {
            items.add(item("s" + i));
        }
        long start = System.currentTimeMillis();
        ExploreSummarizeFetchService.BatchResult result = service.fetchBatch(items, "CZ", 14);
        long elapsed = System.currentTimeMillis() - start;
        assertEquals(seriesCount, result.loaded().size());
        return elapsed;
    }

    @Test
    void aHangingSeriesTimesOutAndIsMarkedFailedWithoutStallingTheWholeBatch() {
        CatalogIndexStore indexStore = mock(CatalogIndexStore.class);
        ManagerSeriesCacheReader cacheReader = mock(ManagerSeriesCacheReader.class);
        ManagerFetchRegistry fetchRegistry = mock(ManagerFetchRegistry.class);
        ExploreSummarizeFetchService service = newService(
                indexStore, mock(CatalogPreviewOrchestrator.class), mock(ConnectorFactory.class), cacheReader, fetchRegistry);
        service.setFetchItemTimeoutSecForTest(1);

        when(cacheReader.readObservations(argThat(m -> m != null && "fast_a".equals(m.get("set_id"))), any()))
                .thenReturn(Optional.of(observations(3)));
        when(cacheReader.readObservations(argThat(m -> m != null && "hangs_b".equals(m.get("set_id"))), any()))
                .thenAnswer(inv -> {
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    return Optional.of(observations(3));
                });
        when(cacheReader.readObservations(argThat(m -> m != null && "fast_c".equals(m.get("set_id"))), any()))
                .thenReturn(Optional.of(observations(3)));

        long start = System.currentTimeMillis();
        ExploreSummarizeFetchService.BatchResult result =
                service.fetchBatch(List.of(item("fast_a"), item("hangs_b"), item("fast_c")), "CZ", 14);
        long elapsedMs = System.currentTimeMillis() - start;

        assertEquals(2, result.loaded().size());
        assertEquals(1, result.failed().size());
        assertEquals("hangs_b", result.failed().get(0).get("set_id"));
        // Proves the 1s timeout actually bounds the wait - without it this would take >=5s.
        assertTrue(elapsedMs < 4_000, "expected the 1s per-item timeout to cut the wait short, took " + elapsedMs + "ms");
    }
}
