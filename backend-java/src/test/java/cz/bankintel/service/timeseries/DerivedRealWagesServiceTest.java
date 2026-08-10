package cz.bankintel.service.timeseries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DerivedRealWagesServiceTest {

    private final DerivedRealWagesService service = new DerivedRealWagesService();

    @Test
    void derivesRealWageIndexFromNominalWagesAndConsumerPrices() {
        Map<String, Object> result = service.deriveRealWageIndex(
                nominalWages("Q"),
                cpi("Q", Map.of("2020Q1", 100.0, "2021Q1", 105.0)));

        assertThat(result).containsEntry("status", "ok");
        assertThat(result).containsEntry("result_type", "derived_series");
        assertThat(result).containsEntry("concept", "real_wages");
        assertThat(result).containsEntry(
                "formula", "real_wage_index_t = nominal_wage_index_t / consumer_price_index_t * base_value");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> observations = (List<Map<String, Object>>) result.get("observations");
        assertThat(observations).hasSize(2);
        assertThat((Double) observations.get(1).get("value")).isCloseTo(104.7619, within(0.0001));
    }

    @Test
    void derivesRealWageGrowthWithExactCompoundingFormula() {
        Map<String, Object> result = service.deriveRealWageGrowth(
                nominalGrowth("Q", Map.of("2021Q1", 10.0)),
                inflationRate("Q", Map.of("2021Q1", 5.0)));

        assertThat(result).containsEntry("status", "ok");
        assertThat(result).containsEntry(
                "formula", "real_growth_t = (1 + nominal_growth_t) / (1 + inflation_rate_t) - 1");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> observations = (List<Map<String, Object>>) result.get("observations");
        assertThat((Double) observations.getFirst().get("value")).isCloseTo(4.7619, within(0.0001));
        assertThat(String.valueOf(result.get("warnings"))).contains("nominal_growth_minus_inflation_is_only_an_approximation");
    }

    @Test
    void rejectsFrequencyMismatch() {
        Map<String, Object> result = service.deriveRealWageIndex(nominalWages("Q"), cpi("M", Map.of("2021-01", 105.0)));

        assertThat(result).containsEntry("status", "not_computed");
        assertThat((List<?>) result.get("warnings")).anyMatch(w -> String.valueOf(w).startsWith("frequency_mismatch"));
    }

    @Test
    void rejectsGeoMismatch() {
        Map<String, Object> result = service.deriveRealWageIndex(
                nominalWages("Q"),
                new DerivedRealWagesService.SourceSeries(
                        "eurostat",
                        "prc_hicp_midx",
                        "HICP Slovakia",
                        "consumer price index",
                        "SK",
                        "Q",
                        "index",
                        "",
                        "",
                        "price_index",
                        "",
                        Map.of("2020Q1", 100.0, "2021Q1", 105.0)));

        assertThat(result).containsEntry("status", "not_computed");
        assertThat((List<?>) result.get("warnings")).anyMatch(w -> String.valueOf(w).startsWith("geo_mismatch"));
    }

    @Test
    void rejectsGovernmentWagesForWholeEconomyRealWages() {
        Map<String, Object> result = service.deriveRealWageIndex(
                new DerivedRealWagesService.SourceSeries(
                        "arad",
                        "1117:government",
                        "Mzdy a platy vládních institucí",
                        "wages",
                        "CZ",
                        "Q",
                        "index",
                        "government",
                        "government_sector",
                        "level",
                        "nominal",
                        Map.of("2020Q1", 100.0, "2021Q1", 110.0)),
                cpi("Q", Map.of("2020Q1", 100.0, "2021Q1", 105.0)));

        assertThat(result).containsEntry("status", "not_computed");
        assertThat(String.valueOf(result.get("warnings")))
                .contains("nominal_wages_not_whole_economy: use a nominal whole-economy average wage series");
    }

    private static DerivedRealWagesService.SourceSeries nominalWages(String frequency) {
        return new DerivedRealWagesService.SourceSeries(
                "csu",
                "MZDKQT1",
                "Průměrné mzdy - časová řada",
                "average wages",
                "CZ",
                frequency,
                "index",
                "total_economy",
                "whole_economy",
                "level",
                "nominal",
                Map.of("2020Q1", 100.0, "2021Q1", 110.0));
    }

    private static DerivedRealWagesService.SourceSeries nominalGrowth(String frequency, Map<String, Double> values) {
        return new DerivedRealWagesService.SourceSeries(
                "csu",
                "MZDY_YOY",
                "Average wage growth",
                "wages",
                "CZ",
                frequency,
                "%",
                "total_economy",
                "whole_economy",
                "growth_rate",
                "nominal",
                values);
    }

    private static DerivedRealWagesService.SourceSeries cpi(String frequency, Map<String, Double> values) {
        return new DerivedRealWagesService.SourceSeries(
                "eurostat",
                "prc_hicp_midx",
                "HICP Czech Republic",
                "consumer price index",
                "CZ",
                frequency,
                "index",
                "",
                "",
                "price_index",
                "",
                values);
    }

    private static DerivedRealWagesService.SourceSeries inflationRate(String frequency, Map<String, Double> values) {
        return new DerivedRealWagesService.SourceSeries(
                "eurostat",
                "inflation",
                "Inflation Czech Republic",
                "inflation",
                "CZ",
                frequency,
                "%",
                "",
                "",
                "growth_rate",
                "",
                values);
    }
}
