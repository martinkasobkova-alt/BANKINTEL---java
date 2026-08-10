package cz.bankintel.explore.manager.refresh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cz.bankintel.connector.AsyncCancellableFetch.AsyncFetchHandle;
import cz.bankintel.connector.ConnectorFetchResult;
import cz.bankintel.connector.EurostatConnector;
import cz.bankintel.explore.manager.refresh.ManagerEurostatCacheRefreshService.RefreshReport;
import cz.bankintel.explore.manager.refresh.ManagerEurostatRefreshTargetBuilder.RefreshTarget;
import cz.bankintel.sources.eurostat.EurostatRateLimiter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.bson.Document;
import org.junit.jupiter.api.Test;

class ManagerEurostatCacheRefreshServiceTest {

    private final ManagerSegmentBundleLoader bundleLoader = mock(ManagerSegmentBundleLoader.class);
    private final ManagerEurostatRefreshTargetBuilder targetBuilder = mock(ManagerEurostatRefreshTargetBuilder.class);
    private final ManagerSeriesCacheWriter writer = mock(ManagerSeriesCacheWriter.class);
    private final EurostatConnector connector = mock(EurostatConnector.class);
    private final EurostatRateLimiter rateLimiter = mock(EurostatRateLimiter.class);

    private final ManagerEurostatCacheRefreshService service =
            new ManagerEurostatCacheRefreshService(bundleLoader, targetBuilder, writer, connector, rateLimiter);

    private static RefreshTarget target(String seriesId, String geo) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("segment_id", "manufacturing_general");
        row.put("series_id", seriesId);
        row.put("dataset_id", seriesId); // unique per target, so mocked fetchAsync calls can be told apart by endpoint
        row.put("title", seriesId);
        row.put("frequency", "M");
        Map<String, Object> queryParams = Map.of("geo", geo, "unit", "I21");
        return new RefreshTarget(row, geo, queryParams);
    }

    private static AsyncFetchHandle successHandle(List<Map<String, Object>> rows) {
        ConnectorFetchResult result = ConnectorFetchResult.ok(rows, Map.of());
        return new AsyncFetchHandle(CompletableFuture.completedFuture(null), CompletableFuture.completedFuture(result));
    }

    private static AsyncFetchHandle failureHandle() {
        ConnectorFetchResult result = ConnectorFetchResult.error(500, Map.of("error", "boom"), Map.of());
        return new AsyncFetchHandle(CompletableFuture.completedFuture(null), CompletableFuture.completedFuture(result));
    }

    @Test
    void oneFailingTargetDoesNotAbortTheRestOfTheBatchAndPreservesOrder() throws InterruptedException {
        when(bundleLoader.eurostatRowsForSegments(any())).thenReturn(List.of());
        RefreshTarget t1 = target("s1", "IT");
        RefreshTarget t2 = target("s2", "IT");
        RefreshTarget t3 = target("s3", "IT");
        when(targetBuilder.buildTargets(any(), any())).thenReturn(List.of(t1, t2, t3));
        when(rateLimiter.tryAcquire(anyLong(), any())).thenReturn(true);

        List<Map<String, Object>> rawRows = List.of(Map.of("date", "2026-06", "value", 100.0), Map.of("date", "2026-05", "value", 99.0));
        when(connector.fetchAsync(argMatchingSeriesEndpoint("s1"))).thenReturn(successHandle(rawRows));
        when(connector.fetchAsync(argMatchingSeriesEndpoint("s2"))).thenReturn(failureHandle());
        when(connector.fetchAsync(argMatchingSeriesEndpoint("s3"))).thenReturn(successHandle(rawRows));
        when(connector.parse(any(), any())).thenReturn(rawRows);

        RefreshReport report = service.refresh(Set.of("manufacturing_general"), Set.of("IT"), true, "manager_series_cache");

        assertEquals(3, report.targets());
        assertEquals(2, report.loaded());
        assertEquals(1, report.unavailable());
        verify(writer, never()).upsertBatch(any(), any());
    }

    @Test
    void waveStatusStoppedWhenFailureRatioExceedsTwentyPercent() throws InterruptedException {
        when(bundleLoader.eurostatRowsForSegments(any())).thenReturn(List.of());
        RefreshTarget t1 = target("s1", "IT");
        RefreshTarget t2 = target("s2", "IT");
        when(targetBuilder.buildTargets(any(), any())).thenReturn(List.of(t1, t2));
        when(rateLimiter.tryAcquire(anyLong(), any())).thenReturn(true);
        when(connector.fetchAsync(any())).thenReturn(failureHandle());

        RefreshReport report = service.refresh(Set.of("manufacturing_general"), Set.of("IT"), true, "manager_series_cache");

        // 2/2 failed = 100% > 20% -> STOPPED.
        assertEquals("STOPPED", report.waveStatus());
        assertEquals(2, report.unavailable());
    }

    @Test
    void dryRunSkipsMongoWriteButLiveRunCallsUpsert() throws InterruptedException {
        when(bundleLoader.eurostatRowsForSegments(any())).thenReturn(List.of());
        RefreshTarget t1 = target("s1", "IT");
        when(targetBuilder.buildTargets(any(), any())).thenReturn(List.of(t1));
        when(rateLimiter.tryAcquire(anyLong(), any())).thenReturn(true);
        List<Map<String, Object>> rawRows = List.of(Map.of("date", "2026-06", "value", 100.0), Map.of("date", "2026-05", "value", 99.0));
        when(connector.fetchAsync(any())).thenReturn(successHandle(rawRows));
        when(connector.parse(any(), any())).thenReturn(rawRows);
        when(writer.upsertBatch(eq("manager_series_cache"), any())).thenReturn(1);

        service.refresh(Set.of("manufacturing_general"), Set.of("IT"), false, "manager_series_cache");

        verify(writer, times(1)).upsertBatch(eq("manager_series_cache"), any());
    }

    private static Map<String, Object> argMatchingSeriesEndpoint(String seriesId) {
        return org.mockito.ArgumentMatchers.argThat(
                source -> source instanceof Map<?, ?> map && ("/" + seriesId).equals(String.valueOf(map.get("endpoint"))));
    }
}
