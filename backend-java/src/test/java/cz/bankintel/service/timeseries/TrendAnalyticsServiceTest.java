package cz.bankintel.service.timeseries;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TrendAnalyticsServiceTest {

    private final TrendAnalyticsService service = new TrendAnalyticsService();

    @Test
    void computesRollingMeansForMonthlySeries() {
        Map<String, Double> series = new LinkedHashMap<>();
        for (int i = 1; i <= 24; i++) {
            series.put(String.format("2023-%02d", i), 100.0 + i);
        }
        Map<String, Object> trend = service.computeTrendMetrics(series, "M");
        assertNotNull(trend.get("rolling_means"));
        assertEquals(24, trend.get("observations_used"));
    }
}
