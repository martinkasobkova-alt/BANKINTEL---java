package cz.bankintel.service.timeseries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RealValuesAnalyticsServiceTest {

    private final RealValuesAnalyticsService service = new RealValuesAnalyticsService();

    @Test
    void computesRealYoyWithExactCompoundingFormulaNotSubtraction() {
        Map<String, Double> nominal = yearly(100, 100, 100, 100, 100, 110);
        Map<String, Double> cpi = yearly(100, 100, 100, 100, 100, 105);

        Map<String, Object> result = service.computeRealMetrics(
                nominal,
                "Nominal wages",
                cpi,
                "Consumer prices",
                new SeriesCompatibilityGuard());

        assertThat(result).containsEntry("status", "ok");
        assertThat(result).containsEntry("real_yoy_formula", "(1 + nominal_yoy) / (1 + inflation_yoy) - 1");
        assertThat((Double) result.get("real_yoy_pct")).isCloseTo(4.7619, within(0.0001));
        assertThat((Double) result.get("approx_real_yoy_pct")).isCloseTo(5.0, within(0.0001));
    }

    private static Map<String, Double> yearly(double... values) {
        Map<String, Double> out = new LinkedHashMap<>();
        int year = 2016;
        for (double value : values) {
            out.put(String.valueOf(year++), value);
        }
        return out;
    }
}
