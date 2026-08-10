package cz.bankintel.service.timeseries;

import cz.bankintel.service.calculations.PeriodAlignment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Generic derived-series engine for real wages. It combines a nominal whole-economy wage series
 * with a compatible consumer-price series, instead of teaching Search V2 to rank a special
 * "real wages" query.
 */
@Service
public class DerivedRealWagesService {

    public static final String USER_NOTE =
            "Přímá řada reálných mezd nebyla v katalogu dostupná. "
                    + "Aplikace výsledek vypočítala z nominálních mezd a spotřebitelských cen.";

    public record SourceSeries(
            String sourceType,
            String setId,
            String title,
            String concept,
            String geo,
            String frequency,
            String unit,
            String institutionalSector,
            String scope,
            String measureType,
            String nominalReal,
            Map<String, Double> values) {}

    public Map<String, Object> deriveRealWageIndex(SourceSeries nominalWageIndex, SourceSeries consumerPriceIndex) {
        return deriveRealWageIndex(nominalWageIndex, consumerPriceIndex, 100.0);
    }

    public Map<String, Object> deriveRealWageIndex(
            SourceSeries nominalWageIndex, SourceSeries consumerPriceIndex, double baseValue) {
        List<String> warnings = validateInputs(nominalWageIndex, consumerPriceIndex);
        if (!warnings.isEmpty()) {
            return notComputed("index", nominalWageIndex, consumerPriceIndex, warnings);
        }
        List<String> common = PeriodAlignment.sortedCommonPeriods(nominalWageIndex.values(), consumerPriceIndex.values());
        Map<String, Double> values = new LinkedHashMap<>();
        for (String period : common) {
            Double nominal = nominalWageIndex.values().get(period);
            Double cpi = consumerPriceIndex.values().get(period);
            if (nominal == null || cpi == null || cpi == 0.0 || !Double.isFinite(nominal) || !Double.isFinite(cpi)) {
                continue;
            }
            values.put(period, nominal / cpi * baseValue);
        }
        if (values.isEmpty()) {
            return notComputed(
                    "index",
                    nominalWageIndex,
                    consumerPriceIndex,
                    List.of("no_valid_common_values: common periods exist, but all values are missing, invalid, or CPI is zero"));
        }
        return ok(
                "index",
                "real_wage_index_t = nominal_wage_index_t / consumer_price_index_t * base_value",
                nominalWageIndex,
                consumerPriceIndex,
                "Index, base_value=" + format(baseValue),
                values,
                List.of());
    }

    public Map<String, Object> deriveRealWageGrowth(SourceSeries nominalGrowth, SourceSeries inflationRate) {
        List<String> warnings = validateInputs(nominalGrowth, inflationRate);
        if (!warnings.isEmpty()) {
            return notComputed("growth", nominalGrowth, inflationRate, warnings);
        }
        List<String> common = PeriodAlignment.sortedCommonPeriods(nominalGrowth.values(), inflationRate.values());
        Map<String, Double> values = new LinkedHashMap<>();
        for (String period : common) {
            Double nominal = nominalGrowth.values().get(period);
            Double inflation = inflationRate.values().get(period);
            if (nominal == null || inflation == null || !Double.isFinite(nominal) || !Double.isFinite(inflation)) {
                continue;
            }
            double nominalRate = toUnitRate(nominal, nominalGrowth.unit());
            double inflationUnitRate = toUnitRate(inflation, inflationRate.unit());
            values.put(period, ((1.0 + nominalRate) / (1.0 + inflationUnitRate) - 1.0) * 100.0);
        }
        if (values.isEmpty()) {
            return notComputed("growth", nominalGrowth, inflationRate, List.of("no_valid_common_values"));
        }
        return ok(
                "growth",
                "real_growth_t = (1 + nominal_growth_t) / (1 + inflation_rate_t) - 1",
                nominalGrowth,
                inflationRate,
                "%",
                values,
                List.of("nominal_growth_minus_inflation_is_only_an_approximation"));
    }

    private static List<String> validateInputs(SourceSeries wage, SourceSeries price) {
        List<String> warnings = new ArrayList<>();
        if (wage == null || price == null) {
            return List.of("missing_input_series");
        }
        if (wage.values() == null || wage.values().isEmpty()) {
            warnings.add("nominal_wage_series_empty");
        }
        if (price.values() == null || price.values().isEmpty()) {
            warnings.add("consumer_price_series_empty");
        }
        if (!sameNonBlank(wage.geo(), price.geo())) {
            warnings.add("geo_mismatch: " + blank(wage.geo()) + " vs " + blank(price.geo()));
        }
        if (!sameNonBlank(wage.frequency(), price.frequency())) {
            warnings.add("frequency_mismatch: " + blank(wage.frequency()) + " vs " + blank(price.frequency()));
        }
        if (!isNominalWholeEconomyWageSeries(wage)) {
            warnings.add("nominal_wages_not_whole_economy: use a nominal whole-economy average wage series");
        }
        if (!isConsumerPriceSeries(price)) {
            warnings.add("consumer_price_series_not_recognized: use CPI/HICP consumer-price index or inflation rate");
        }
        if (wage.values() != null && price.values() != null) {
            int common = PeriodAlignment.sortedCommonPeriods(wage.values(), price.values()).size();
            if (common == 0) {
                warnings.add("no_common_period");
            }
        }
        return warnings;
    }

    private static boolean isNominalWholeEconomyWageSeries(SourceSeries series) {
        String conceptBlob = folded(series.concept() + " " + series.title() + " " + series.measureType());
        if (!containsAny(conceptBlob, "wage", "mzda", "mzdy", "earnings", "salary")) {
            return false;
        }
        String sector = folded(series.institutionalSector());
        if (containsAny(sector, "government", "public", "vlada", "vlád", "s13")) {
            return false;
        }
        String measure = folded(series.measureType() + " " + series.concept() + " " + series.title());
        if (containsAny(measure, "compensation of employees", "compensation", "labor cost", "labour cost", "nahrady")) {
            return false;
        }
        String nominalReal = folded(series.nominalReal());
        if ("real".equals(nominalReal)) {
            return false;
        }
        String scope = folded(series.scope());
        return scope.isBlank()
                || containsAny(scope, "total economy", "whole economy", "national", "all", "celkem")
                || !containsAny(scope, "sector", "industry", "subgroup", "occupation", "government");
    }

    private static boolean isConsumerPriceSeries(SourceSeries series) {
        String blob = folded(series.concept() + " " + series.title() + " " + series.measureType());
        return containsAny(blob, "cpi", "hicp", "consumer price", "inflation", "spotrebitelsk");
    }

    private static Map<String, Object> ok(
            String method,
            String formula,
            SourceSeries wage,
            SourceSeries price,
            String unit,
            Map<String, Double> values,
            List<String> warnings) {
        Map<String, Object> out = base("ok", method, formula, wage, price, warnings);
        out.put("unit", unit);
        out.put("observations", values.entrySet().stream()
                .map(entry -> Map.of("period", entry.getKey(), "value", entry.getValue()))
                .toList());
        out.put("latest_period", lastKey(values));
        out.put("latest_value", values.get(lastKey(values)));
        return out;
    }

    private static Map<String, Object> notComputed(
            String method, SourceSeries wage, SourceSeries price, List<String> warnings) {
        return base("not_computed", method, "", wage, price, warnings);
    }

    private static Map<String, Object> base(
            String status,
            String method,
            String formula,
            SourceSeries wage,
            SourceSeries price,
            List<String> warnings) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        out.put("result_type", "derived_series");
        out.put("concept", "real_wages");
        out.put("method", method);
        out.put("formula", formula);
        out.put("input_series", List.of(inputSeries(wage, "nominal_wages"), inputSeries(price, "consumer_prices")));
        out.put("geo", wage == null ? "" : blank(wage.geo()));
        out.put("frequency", wage == null ? "" : blank(wage.frequency()));
        out.put("methodology_note", USER_NOTE);
        out.put("warnings", warnings == null ? List.of() : warnings);
        return out;
    }

    private static Map<String, Object> inputSeries(SourceSeries series, String role) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("role", role);
        out.put("source_type", series == null ? "" : blank(series.sourceType()));
        out.put("set_id", series == null ? "" : blank(series.setId()));
        out.put("title", series == null ? "" : blank(series.title()));
        out.put("concept", series == null ? "" : blank(series.concept()));
        out.put("geo", series == null ? "" : blank(series.geo()));
        out.put("frequency", series == null ? "" : blank(series.frequency()));
        out.put("unit", series == null ? "" : blank(series.unit()));
        return out;
    }

    private static double toUnitRate(double value, String unit) {
        String u = folded(unit);
        if (u.contains("%") || u.contains("percent") || u.equals("pc") || Math.abs(value) > 1.0) {
            return value / 100.0;
        }
        return value;
    }

    private static boolean sameNonBlank(String left, String right) {
        if (left == null || left.isBlank() || right == null || right.isBlank()) {
            return false;
        }
        return left.trim().equalsIgnoreCase(right.trim());
    }

    private static String lastKey(Map<String, Double> values) {
        List<String> periods = TimeSeriesMath.sortedPeriods(values);
        return periods.isEmpty() ? "" : periods.get(periods.size() - 1);
    }

    private static boolean containsAny(String blob, String... needles) {
        for (String needle : needles) {
            if (blob.contains(folded(needle))) {
                return true;
            }
        }
        return false;
    }

    private static String folded(String value) {
        return java.text.Normalizer.normalize(value == null ? "" : value, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .trim();
    }

    private static String blank(String value) {
        return value == null ? "" : value.trim();
    }

    private static String format(double value) {
        if (value == Math.rint(value)) {
            return Long.toString(Math.round(value));
        }
        return Double.toString(value);
    }
}
