package cz.bankintel.service.timeseries;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TimeSeriesMetricsServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void exposesMeanMedianStandardDeviationAndVariance() {
        TimeSeriesMetricsService service = new TimeSeriesMetricsService();

        Map<String, Object> metrics = service.computeMetrics(
                Map.of("2022", 1.0, "2023", 2.0, "2024", 3.0), "2022");
        Map<String, Object> history = (Map<String, Object>) metrics.get("history");

        assertEquals(2.0, (Double) history.get("mean"), 1e-12);
        assertEquals(2.0, (Double) history.get("median"), 1e-12);
        assertEquals(Math.sqrt(2.0 / 3.0), (Double) history.get("standard_deviation"), 1e-12);
        assertEquals(2.0 / 3.0, (Double) history.get("variance"), 1e-12);
    }
}
