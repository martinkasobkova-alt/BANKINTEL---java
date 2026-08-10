package cz.bankintel.service.timeseries;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TimeSeriesMathTest {

    private static Map<String, Double> monthly(double... values) {
        Map<String, Double> out = new LinkedHashMap<>();
        int year = 2020;
        int month = 1;
        for (double v : values) {
            out.put(String.format("%d-%02d", year, month), v);
            month++;
            if (month > 12) {
                month = 1;
                year++;
            }
        }
        return out;
    }

    @Test
    void lastAndPreviousValueRespectPeriodOrder() {
        Map<String, Double> series = monthly(10, 20, 30);
        assertEquals(30.0, TimeSeriesMath.lastValue(series));
        assertEquals(20.0, TimeSeriesMath.previousValue(series));
        assertEquals(10.0, TimeSeriesMath.firstValue(series));
    }

    @Test
    void distanceFromMaxIsNegativeBelowPeak() {
        Map<String, Double> series = monthly(10, 20, 15);
        TimeSeriesMath.Distance d = TimeSeriesMath.distanceFromMax(series);
        assertEquals(-5.0, d.absolute(), 1e-9);
        assertEquals(-25.0, d.percent(), 1e-9);
    }

    @Test
    void percentileOfLastValueIsHundredForNewHigh() {
        Map<String, Double> series = monthly(1, 2, 3, 4, 5);
        assertEquals(100.0, TimeSeriesMath.percentileOfLastValue(series), 0.01);
    }

    @Test
    void distanceFromMeanInStdDevsIsZeroForConstantSeries() {
        Map<String, Double> series = monthly(5, 5, 5, 5);
        assertEquals(0.0, TimeSeriesMath.distanceFromMeanInStdDevs(series));
    }

    @Test
    void countUpDownPeriodsTallyMovementDirections() {
        Map<String, Double> series = monthly(10, 12, 12, 8);
        TimeSeriesMath.UpDownCounts counts = TimeSeriesMath.countUpDownPeriods(series);
        assertEquals(1, counts.up());
        assertEquals(1, counts.down());
        assertEquals(1, counts.flat());
    }

    @Test
    void maxDrawdownFindsPeakToTrough() {
        Map<String, Double> series = monthly(100, 110, 90, 95);
        Optional<TimeSeriesMath.DrawdownSummary> dd = TimeSeriesMath.maxDrawdown(series);
        assertTrue(dd.isPresent());
        assertEquals(110.0, dd.get().peakValue());
        assertEquals(90.0, dd.get().troughValue());
        assertTrue(dd.get().drawdownPct() < 0);
    }

    @Test
    void cagrComputesAnnualizedGrowthOverYears() {
        // Needs >= 4 contiguous annual periods for the periodicity inference to confidently
        // treat "steps" as literal years rather than falling back to a generic quarterly guess.
        Map<String, Double> series = new LinkedHashMap<>();
        series.put("2016", 100.0);
        series.put("2017", 100.0 * 1.10);
        series.put("2018", 100.0 * Math.pow(1.10, 2));
        series.put("2019", 100.0 * Math.pow(1.10, 3));
        series.put("2020", 100.0 * Math.pow(1.10, 4));
        Double cagr = TimeSeriesMath.cagr(series, "2016", "2020");
        assertNotNull(cagr);
        assertEquals(10.0, cagr, 0.1);
    }

    @Test
    void linearTrendFitsPerfectLineWithR2One() {
        Map<String, Double> series = monthly(1, 2, 3, 4, 5);
        TimeSeriesMath.TrendFit fit = TimeSeriesMath.linearTrend(series);
        assertEquals(1.0, fit.slopePerStep(), 1e-9);
        assertEquals(1.0, fit.r2(), 1e-9);
    }

    @Test
    void logLinearTrendRecoversConstantGrowthRate() {
        Map<String, Double> series = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) {
            series.put(String.format("2020-%02d", i + 1), 100.0 * Math.pow(1.05, i));
        }
        Optional<TimeSeriesMath.TrendFit> fit = TimeSeriesMath.logLinearTrend(series);
        assertTrue(fit.isPresent());
        assertEquals(1.0, fit.get().r2(), 1e-6);
    }

    @Test
    void logLinearTrendEmptyForNonPositiveSeries() {
        Map<String, Double> series = monthly(-1, 2, 3);
        assertTrue(TimeSeriesMath.logLinearTrend(series).isEmpty());
    }

    @Test
    void multiWindowTrendSlopesSkipsWindowsLongerThanSeries() {
        Map<String, Double> series = monthly(1, 2, 3, 4, 5);
        Map<Integer, Double> slopes = TimeSeriesMath.multiWindowTrendSlopes(series, new int[] {3, 12, 60});
        assertTrue(slopes.containsKey(3));
        assertFalse(slopes.containsKey(12));
        assertFalse(slopes.containsKey(60));
    }

    @Test
    void correlationDetectsPerfectPositiveRelationship() {
        Map<String, Double> a = monthly(1, 2, 3, 4, 5);
        Map<String, Double> b = monthly(10, 20, 30, 40, 50);
        assertEquals(1.0, TimeSeriesMath.correlation(a, b), 1e-9);
    }

    @Test
    void correlationNullWithTooFewCommonPeriods() {
        Map<String, Double> a = monthly(1, 2);
        Map<String, Double> b = monthly(10, 20);
        assertNull(TimeSeriesMath.correlation(a, b));
    }

    @Test
    void bestLagFindsTheShiftWithStrongestCorrelation() {
        // Irregular (non-linear) base sequence so distinct lags genuinely differ in correlation
        // (an affine/monotonic base would make every lag correlate perfectly, defeating the test).
        double[] base = {3, 7, 1, 9, 4, 8, 2, 6, 5, 10, 0, 4};
        Map<String, Double> b = monthly(base);
        Map<String, Double> a = new LinkedHashMap<>();
        var periods = TimeSeriesMath.sortedPeriods(b);
        // b leads a by 2 steps: a(t) == b(t-2)
        for (int i = 2; i < periods.size(); i++) {
            a.put(periods.get(i), b.get(periods.get(i - 2)));
        }
        TimeSeriesMath.LaggedCorrelation best = TimeSeriesMath.bestLag(a, b, new int[] {0, 1, 2, 3, 4});
        assertNotNull(best);
        assertEquals(2, best.lag());
        assertEquals(1.0, best.r(), 1e-9);
    }

    @Test
    void simpleElasticityMatchesTwoPointPercentRatio() {
        Map<String, Double> y = monthly(100, 110);
        Map<String, Double> x = monthly(10, 12);
        Double elasticity = TimeSeriesMath.simpleElasticity(y, x);
        assertNotNull(elasticity);
        assertEquals(0.10 / 0.20, elasticity, 1e-9);
    }

    @Test
    void betaVsBenchmarkRecoversKnownSlope() {
        Map<String, Double> benchmark = monthly(100, 101, 103, 102, 106, 108);
        Map<String, Double> series = new LinkedHashMap<>();
        var periods = TimeSeriesMath.sortedPeriods(benchmark);
        double level = 50.0;
        series.put(periods.get(0), level);
        for (int i = 1; i < periods.size(); i++) {
            double benchReturn = (benchmark.get(periods.get(i)) - benchmark.get(periods.get(i - 1))) / benchmark.get(periods.get(i - 1));
            level *= 1 + 1.5 * benchReturn;
            series.put(periods.get(i), level);
        }
        Double beta = TimeSeriesMath.betaVsBenchmark(series, benchmark);
        assertNotNull(beta);
        assertEquals(1.5, beta, 0.01);
    }

    @Test
    void indexToRebasesSeriesToGivenValueAtBasePeriod() {
        Map<String, Double> series = monthly(50, 75, 100);
        Map<String, Double> indexed = TimeSeriesMath.indexTo(series, null, 100.0);
        var periods = TimeSeriesMath.sortedPeriods(indexed);
        assertEquals(100.0, indexed.get(periods.get(0)), 0.01);
        assertEquals(150.0, indexed.get(periods.get(1)), 0.01);
        assertEquals(200.0, indexed.get(periods.get(2)), 0.01);
    }

    @Test
    void yoyPercentUsesInferredMonthlyLag() {
        Map<String, Double> series = new LinkedHashMap<>();
        for (int i = 0; i < 24; i++) {
            series.put(String.format("2020-%02d", i % 12 + 1).replace("2020", i < 12 ? "2020" : "2021"), 100.0 + i);
        }
        Map<String, Double> yoy = TimeSeriesMath.yoyPercent(series);
        assertFalse(yoy.isEmpty());
    }
}
