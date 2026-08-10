package cz.bankintel.service.timeseries;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TimeSeriesResamplerTest {

    @Test
    void resamplesDailyToMonthlyUsingLastValueOfEachMonth() {
        Map<String, Double> daily = new LinkedHashMap<>();
        daily.put("2026-06-29", 281.74);
        daily.put("2026-06-30", 289.36);
        daily.put("2026-07-01", 294.38);
        daily.put("2026-07-02", 308.63);
        daily.put("2026-07-31", 308.91);

        Map<String, Double> monthly = TimeSeriesResampler.resample(daily, "D", "M");

        assertThat(monthly).containsExactly(
                Map.entry("2026-06", 289.36),
                Map.entry("2026-07", 308.91));
    }

    @Test
    void resamplesDailyToWeeklyAndYearly() {
        Map<String, Double> daily = new LinkedHashMap<>();
        daily.put("2026-01-01", 1.0);
        daily.put("2026-06-15", 2.0);
        daily.put("2026-12-31", 3.0);

        Map<String, Double> yearly = TimeSeriesResampler.resample(daily, "D", "Y");
        assertThat(yearly).containsExactly(Map.entry("2026", 3.0));

        Map<String, Double> weekly = TimeSeriesResampler.resample(daily, "D", "W");
        assertThat(weekly).hasSize(3);
    }

    @Test
    void returnsUnchangedWhenTargetIsNotCoarserThanNative() {
        Map<String, Double> monthly = new LinkedHashMap<>();
        monthly.put("2026-01", 1.0);
        monthly.put("2026-02", 2.0);

        assertThat(TimeSeriesResampler.resample(monthly, "M", "D")).isEqualTo(monthly);
        assertThat(TimeSeriesResampler.resample(monthly, "M", "M")).isEqualTo(monthly);
    }

    @Test
    void returnsUnchangedWhenPeriodsAreUnparsable() {
        Map<String, Double> quarterly = new LinkedHashMap<>();
        quarterly.put("2024-Q1", 1.0);
        quarterly.put("2024-Q2", 2.0);
        quarterly.put("2024-Q3", 3.0);

        assertThat(TimeSeriesResampler.resample(quarterly, "Q", "Y")).isEqualTo(quarterly);
    }

    @Test
    void returnsUnchangedWhenTargetFrequencyBlankOrUnknown() {
        Map<String, Double> daily = Map.of("2026-01-01", 1.0);
        assertThat(TimeSeriesResampler.resample(daily, "D", "")).isEqualTo(daily);
        assertThat(TimeSeriesResampler.resample(daily, "D", "bogus")).isEqualTo(daily);
    }
}
