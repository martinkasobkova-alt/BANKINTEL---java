package cz.bankintel.search.analytics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Deterministic Czech narrative layer for analytics results — fills sentence templates strictly
 * from numeric values already present in the analytics JSON. No LLM arithmetic.
 */
@Service
public class AnalyticsNarrativeService {

    @SuppressWarnings("unchecked")
    public Map<String, Object> buildNarrative(Map<String, Object> analysis, String domainLabelCz) {
        Map<String, Object> out = new LinkedHashMap<>();
        Context ctx = fromContext(analysis, domainLabelCz);

        Map<String, Object> metrics = analysis.get("metrics") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Map<String, Object> current = metrics.get("current") instanceof Map<?, ?> c ? (Map<String, Object>) c : Map.of();
        Map<String, Object> change = metrics.get("change") instanceof Map<?, ?> ch ? (Map<String, Object>) ch : Map.of();
        Map<String, Object> distribution = metrics.get("distribution") instanceof Map<?, ?> d ? (Map<String, Object>) d : Map.of();

        Double lastValue = num(current.get("last_value"));
        String lastPeriod = str(current.get("last_period"));
        Double yoyPct = num(change.get("yoy_pct_last"));
        Double momPct = num(change.get("mom_pct_last"));
        Double percentile = num(distribution.get("percentile_of_last_value"));

        List<Map<String, Object>> keyNumbers = buildKeyNumbers(ctx, lastValue, lastPeriod, yoyPct, momPct, percentile);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> forecasts = analysis.get("forecasts") instanceof List<?> f ? (List<Map<String, Object>>) f : List.of();
        Map<String, Object> headlineForecast = forecasts.isEmpty() ? Map.of() : forecasts.get(Math.min(forecasts.size() - 1, 2));

        out.put("executive_summary", buildExecutiveSummary(ctx, lastPeriod, lastValue, yoyPct, percentile));
        out.put("main_insight", buildMainInsight(ctx, yoyPct, momPct, percentile));
        out.put("forecast_sentence", buildForecastSentence(headlineForecast, ctx.unit));
        out.put("article_headline", headline(ctx, yoyPct));
        out.put("manager_sentence", buildExecutiveSummary(ctx, lastPeriod, lastValue, yoyPct, percentile));
        out.put("chart_caption", chartCaption(ctx, lastPeriod, lastValue));
        out.put("watch_next", buildWatchNext(analysis));
        out.put("key_numbers", keyNumbers);
        out.put("chart_annotations", chartAnnotations(analysis));
        out.put("methodology_note", buildMethodologyNote(analysis, ctx));
        out.put("methodology_sections", buildMethodologySections(analysis, ctx));
        out.put("caveats", extractQualityWarnings(analysis));
        return out;
    }

    private static List<Map<String, Object>> buildKeyNumbers(
            Context ctx,
            Double lastValue,
            String lastPeriod,
            Double yoyPct,
            Double momPct,
            Double percentile) {
        List<Map<String, Object>> keyNumbers = new ArrayList<>();
        if (lastValue != null) {
            keyNumbers.add(keyNumber("Poslední hodnota", lastValue, lastPeriod, ctx.unit));
        }
        if (yoyPct != null) {
            keyNumbers.add(keyNumber("Meziroční změna", yoyPct, lastPeriod, "%"));
        }
        if (momPct != null) {
            keyNumbers.add(keyNumber("Změna oproti předchozímu období", momPct, lastPeriod, "%"));
        }
        if (percentile != null) {
            keyNumbers.add(keyNumber("Poloha v historii", percentile, lastPeriod, "percentil"));
        }
        return keyNumbers;
    }

    private static String buildExecutiveSummary(
            Context ctx, String period, Double lastValue, Double yoyPct, Double percentile) {
        StringBuilder sb = new StringBuilder();
        sb.append("Ukazatel „").append(ctx.seriesName).append("“");
        if (ctx.domainLabel != null && !ctx.domainLabel.isBlank()) {
            sb.append(" (").append(ctx.domainLabel).append(")");
        }
        if (period != null && lastValue != null) {
            sb.append(" dosáhl v období ")
                    .append(AnalyticsValueFormatter.formatPeriod(period))
                    .append(" hodnoty ")
                    .append(AnalyticsValueFormatter.formatValue(lastValue, ctx.unit));
        }
        if (yoyPct != null) {
            sb.append(". Oproti stejnému období loni je to ")
                    .append(directionPhrase(yoyPct))
                    .append(" o ")
                    .append(AnalyticsValueFormatter.formatDecimal(Math.abs(yoyPct), 1))
                    .append(" %");
        }
        if (percentile != null) {
            sb.append(". V historii dat je poslední hodnota na ")
                    .append(AnalyticsValueFormatter.formatDecimal(percentile, 0))
                    .append(". percentilu");
        }
        sb.append(".");
        return sb.toString();
    }

    private static String buildMainInsight(Context ctx, Double yoyPct, Double momPct, Double percentile) {
        if (yoyPct != null && Math.abs(yoyPct) >= 0.05) {
            String tempo = Math.abs(yoyPct) > 5 ? "výrazné" : "mírné";
            return "Meziroční dynamika ukazatele „"
                    + ctx.seriesName
                    + "“ je "
                    + tempo
                    + " — "
                    + (yoyPct > 0 ? "řada roste" : "řada klesá")
                    + " o "
                    + AnalyticsValueFormatter.formatDecimal(Math.abs(yoyPct), 1)
                    + " % oproti předchozímu roku.";
        }
        if (momPct != null && Math.abs(momPct) >= 0.05) {
            return "Krátkodobě (oproti předchozímu období) se ukazatel „"
                    + ctx.seriesName
                    + "“ "
                    + (momPct > 0 ? "zvyšuje" : "snižuje")
                    + " o "
                    + AnalyticsValueFormatter.formatDecimal(Math.abs(momPct), 1)
                    + " %.";
        }
        if (percentile != null && percentile >= 80) {
            return "Aktuální hodnota je vysoko v historickém rozpětí — blíže k horním hodnotám než k dlouhodobému průměru.";
        }
        if (percentile != null && percentile <= 20) {
            return "Aktuální hodnota je nízko v historickém rozpětí — blíže k dolním hodnotám než k dlouhodobému průměru.";
        }
        return "Dynamika ukazatele „" + ctx.seriesName + "“ je v rámci běžného historického pásm a bez extrémní odchylky.";
    }

    private static String buildForecastSentence(Map<String, Object> forecastPoint, String unit) {
        if (forecastPoint.isEmpty()) {
            return "";
        }
        Double p50 = num(forecastPoint.get("p50"));
        String horizon = str(forecastPoint.get("horizon"));
        Double p10 = num(forecastPoint.get("p10"));
        Double p90 = num(forecastPoint.get("p90"));
        if (p50 == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("Technický forecast (baseline scénář) ukazuje modelový odhad ");
        sb.append(AnalyticsValueFormatter.formatValue(p50, unit));
        if (!horizon.isBlank()) {
            sb.append(" v horizontu ").append(horizon);
        }
        if (p10 != null && p90 != null) {
            sb.append("; interval nejistoty je ")
                    .append(AnalyticsValueFormatter.formatValue(p10, unit))
                    .append(" až ")
                    .append(AnalyticsValueFormatter.formatValue(p90, unit));
        }
        sb.append(". Jde o modelový odhad, nikoli jistou predikci.");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> buildWatchNext(Map<String, Object> analysis) {
        List<String> watch = new ArrayList<>();
        List<Map<String, Object>> anomalies = analysis.get("anomalies") instanceof List<?> a ? (List<Map<String, Object>>) a : List.of();
        if (!anomalies.isEmpty()) {
            watch.add("Sledovat signál: " + str(anomalies.get(0).get("description")));
        }
        List<Map<String, Object>> relationships =
                analysis.get("relationships") instanceof List<?> r ? (List<Map<String, Object>>) r : List.of();
        for (Map<String, Object> rel : relationships) {
            if (!"ok".equals(str(rel.get("status")))) {
                continue;
            }
            Object bestLag = rel.get("best_lag");
            if (bestLag instanceof Map<?, ?> bl && bl.get("r") instanceof Number rVal && Math.abs(rVal.doubleValue()) > 0.5) {
                watch.add(
                        "Vztah k „"
                                + AnalyticsValueFormatter.humanRelationshipLabel(rel)
                                + "“ — nejsilnější souvislost se zpožděním "
                                + bl.get("lag")
                                + " "
                                + lagUnit(bl.get("lag_unit"))
                                + " (korelace r="
                                + AnalyticsValueFormatter.formatDecimal(rVal.doubleValue(), 2)
                                + ").");
                break;
            }
        }
        if (watch.isEmpty()) {
            watch.add("Sledovat další publikace zdrojových dat a případné revize historických hodnot.");
        }
        return watch;
    }

    @SuppressWarnings("unchecked")
    private static String buildMethodologyNote(Map<String, Object> analysis, Context ctx) {
        List<String> completed = completedCalculations(analysis);
        StringBuilder sb = new StringBuilder();
        sb.append("Všechna čísla v tomto přehledu jsou spočítána deterministicky v backendu (Java, modul TimeSeriesMath). ");
        sb.append("Žádné hodnoty nevypočítává AI — jazyková interpretace jen popisuje již hotová čísla. ");
        sb.append("Analyzovaná řada: „").append(ctx.seriesName).append("“");
        if (ctx.frequency != null && !ctx.frequency.isBlank()) {
            sb.append(", periodicita ").append(ctx.frequency);
        }
        if (ctx.unit != null && !ctx.unit.isBlank()) {
            sb.append(", jednotka ").append(ctx.unit);
        }
        sb.append(". ");
        if (!completed.isEmpty()) {
            sb.append("Provedené výpočty: ").append(String.join(", ", completed)).append(". ");
        }
        sb.append("Velké absolutní hodnoty jsou v textu zaokrouhleny na miliony/miliardy. ");
        sb.append("Korelace a regrese neimplikují kauzalitu. ");
        sb.append("Forecast je technický odhad se scénářovým rámcem, nikoli jistá predikce.");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> buildMethodologySections(Map<String, Object> analysis, Context ctx) {
        List<Map<String, String>> sections = new ArrayList<>();
        sections.add(section(
                "Co bylo analyzováno",
                "Cílová řada: „"
                        + ctx.seriesName
                        + "“"
                        + (ctx.geo != null && !ctx.geo.isBlank() ? ", geografie " + ctx.geo : "")
                        + ". "
                        + "Doména: "
                        + (ctx.domainLabel != null && !ctx.domainLabel.isBlank() ? ctx.domainLabel : "obecný makro ukazatel")
                        + "."));

        if (analysis.get("metrics") instanceof Map<?, ?>) {
            sections.add(section(
                    "Základní metriky",
                    "Poslední a předchozí hodnota, min/max/průměr/medián, meziroční a meziobdobní změna, percentil poslední hodnoty v historii, volatilita, drawdown a klouzavé trendy (3/6/12/24/60 období)."));
        }
        if (analysis.get("indexation") instanceof Map<?, ?>) {
            sections.add(section(
                    "Indexace",
                    "Index 100 k prvnímu období, k roku 2019 (nebo zvolenému base period) a z-score / percentil poslední hodnoty v historii."));
        }
        if (analysis.get("trend") instanceof Map<?, ?>) {
            sections.add(section(
                    "Trend",
                    "Sklon lineárního trendu, síla trendu a klouzavé průměry — vše z časové řady bez LLM."));
        }
        if (analysis.get("anomalies") instanceof List<?> list && !list.isEmpty()) {
            sections.add(section(
                    "Anomálie",
                    "Heuristické signály: extrémní hodnoty, z-score odchylky, skok volatility a strukturální posuny (mean-shift test)."));
        }
        if (analysis.get("relationships") instanceof List<?> list && !list.isEmpty()) {
            sections.add(section(
                    "Ekonomické vztahy",
                    "Pearsonova korelace a lagged korelace vůči souvisejícím řadám z ontologie (inflace, sazby, HDP…). "
                            + "Názvy vztahů jsou ekonomické koncepty, ne technická ID katalogu."));
        }
        if (analysis.get("real_values") instanceof Map<?, ?> rv && "ok".equals(str(rv.get("status")))) {
            sections.add(section(
                    "Reálné hodnoty",
                    "Nominální dynamika deflátorována inflační řadou ze stejné geografie — reálný YoY vs. nominální YoY."));
        }
        if (analysis.get("forecasts") instanceof List<?> list && !list.isEmpty()) {
            sections.add(section(
                    "Forecast",
                    "Technický forecast z Java enginu: rolling-origin backtest, výběr modelu a interval p10–p90. "
                            + "Vstupní řady procházejí kontrolou kvality a ověřením přínosu v backtestu."));
        }
        if (analysis.get("scenarios") instanceof List<?> list && !list.isEmpty()) {
            sections.add(section(
                    "Scénáře",
                    "Scénářový engine aplikuje předdefinované nebo parametrizované šoky na role-based drivery (optimistický/pesimistický/stress)."));
        }
        sections.add(section(
                "Formát čísel",
                "Procenta a sazby: 1 desetinné místo. Absolutní hodnoty ≥ 1 mil.: kompaktní tvar (tis./mil./mld./bil.). "
                        + "Období: čitelné datum nebo kvartál (např. 31. 3. 2026, 2025 Q1)."));
        return sections;
    }

    @SuppressWarnings("unchecked")
    private static List<String> completedCalculations(Map<String, Object> analysis) {
        if (!(analysis.get("planner") instanceof Map<?, ?> planner)) {
            return List.of();
        }
        Object completed = planner.get("calculation_types_completed");
        if (!(completed instanceof List<?> list)) {
            return List.of();
        }
        List<String> labels = new ArrayList<>();
        for (Object item : list) {
            labels.add(calculationLabel(String.valueOf(item)));
        }
        return labels;
    }

    private static String calculationLabel(String key) {
        return switch (key) {
            case "basic_metrics" -> "základní metriky";
            case "indexation" -> "indexace";
            case "trend" -> "trend";
            case "comparison" -> "srovnání";
            case "relationships" -> "ekonomické vztahy";
            case "anomalies" -> "anomálie";
            case "real_values" -> "reálné hodnoty";
            case "forecast" -> "forecast";
            case "scenarios" -> "scénáře";
            default -> key.replace('_', ' ');
        };
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> chartAnnotations(Map<String, Object> analysis) {
        List<Map<String, Object>> annotations = new ArrayList<>();
        Map<String, Object> indexation = analysis.get("indexation") instanceof Map<?, ?> i ? (Map<String, Object>) i : Map.of();
        Double idx2019 = num(indexation.get("index_100_at_2019_last"));
        if (idx2019 != null) {
            annotations.add(Map.of("label", "Index 2019=100", "value", idx2019));
        }
        return annotations;
    }

    @SuppressWarnings("unchecked")
    private static List<String> extractQualityWarnings(Map<String, Object> analysis) {
        if (analysis.get("quality_warnings") instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private static Map<String, Object> keyNumber(String label, double value, String period, String unit) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", label);
        row.put("value", value);
        row.put("display_value", AnalyticsValueFormatter.formatValue(value, unit));
        row.put("period", AnalyticsValueFormatter.formatPeriod(period));
        if (unit != null && !unit.isBlank()) {
            row.put("unit", unit);
        }
        return row;
    }

    private static Map<String, String> section(String title, String body) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("title", title);
        row.put("body", body);
        return row;
    }

    private static String headline(Context ctx, Double yoyPct) {
        if (yoyPct != null) {
            return "„"
                    + ctx.seriesName
                    + "“ — meziročně "
                    + (yoyPct >= 0 ? "+" : "")
                    + AnalyticsValueFormatter.formatDecimal(yoyPct, 1)
                    + " %";
        }
        return "„" + ctx.seriesName + "“ — analytický přehled";
    }

    private static String chartCaption(Context ctx, String period, Double value) {
        if (period != null && value != null) {
            return "„"
                    + ctx.seriesName
                    + "“, poslední období "
                    + AnalyticsValueFormatter.formatPeriod(period)
                    + " = "
                    + AnalyticsValueFormatter.formatValue(value, ctx.unit);
        }
        return "„" + ctx.seriesName + "“";
    }

    private static String directionPhrase(double yoyPct) {
        if (yoyPct > 0.05) {
            return "nárůst";
        }
        if (yoyPct < -0.05) {
            return "pokles";
        }
        return "změna";
    }

    private static String lagUnit(Object unit) {
        String u = str(unit);
        return u.isBlank() ? "období" : u;
    }

    @SuppressWarnings("unchecked")
    private static Context fromContext(Map<String, Object> analysis, String domainLabelCz) {
        Context ctx = new Context();
        ctx.domainLabel = domainLabelCz != null && !domainLabelCz.isBlank() ? domainLabelCz : "ukazatel";
        if (analysis.get("target_resolution") instanceof Map<?, ?> tr) {
            ctx.seriesName = AnalyticsValueFormatter.humanSeriesLabel(
                    str(tr.get("target_series_name")), str(analysis.get("series_id")));
            ctx.unit = str(tr.get("unit"));
            ctx.frequency = str(tr.get("frequency"));
            ctx.geo = str(tr.get("geo"));
        } else {
            ctx.seriesName = AnalyticsValueFormatter.humanSeriesLabel(null, str(analysis.get("series_id")));
        }
        return ctx;
    }

    private static Double num(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return null;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static final class Context {
        private String seriesName = "vybraná datová řada";
        private String domainLabel = "";
        private String unit = "";
        private String frequency = "";
        private String geo = "";
    }
}
