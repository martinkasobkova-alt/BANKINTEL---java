package cz.bankintel.search.forecast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

import cz.bankintel.search.analytics.AnalyticsValueFormatter;

/**
 * Deterministic Czech "narrative" templating layer — the LLM-forbidden step from the product
 * spec ("LLM nesmí dopočítávat čísla"). Every number placed into a sentence below comes straight
 * out of the Java forecast result (already computed); this class only formats/concatenates.
 *
 * <p>Hedged language only (per the spec's "critical rule"): "technický forecast", "baseline
 * scénář", "modelový odhad", "interval nejistoty", "při daných předpokladech", "výsledek je
 * citlivý na...". Never "bude", "určitě", "model ví", "AI predikuje přesně".
 */
@Service
public class ForecastNarrativeService {

    public Map<String, Object> buildNarrative(Map<String, Object> forecastResponse, String domainLabelCz) {
        Map<String, Object> targetSeries = asMap(forecastResponse.get("target_series"));
        Map<String, Object> narrativeValues = asMap(forecastResponse.get("narrative_values"));
        Map<String, Object> dataQuality = asMap(forecastResponse.get("data_quality"));
        Map<String, Object> modelSelection = asMap(forecastResponse.get("model_selection"));
        Map<String, Object> backtest = asMap(forecastResponse.get("backtest"));

        String targetName = str(targetSeries.get("name"));
        String unit = unitSuffix(str(targetSeries.get("unit")));
        String horizonLabel = str(narrativeValues.get("horizon_label"));
        Double p10 = num(narrativeValues.get("p10"));
        Double p50 = num(narrativeValues.get("p50"));
        Double p90 = num(narrativeValues.get("p90"));
        Double changePct = num(narrativeValues.get("change_pct"));
        Double lastValue = num(targetSeries.get("last_value"));
        String lastDate = str(targetSeries.get("last_date"));
        String selectedModel = str(modelSelection.get("selected_model"));
        String branch = str(modelSelection.get("branch"));
        boolean fallbackUsed = Boolean.TRUE.equals(modelSelection.get("fallback_used"));
        String status = str(dataQuality.get("status"));

        List<String> drivers = new ArrayList<>();
        for (String key : List.of("top_driver_1", "top_driver_2", "top_driver_3")) {
            String d = str(narrativeValues.get(key));
            if (!d.isBlank()) {
                drivers.add(d);
            }
        }
        List<String> topSelectedFeatureNames = topSelectedFeatureNames(forecastResponse);

        Map<String, Object> out = new LinkedHashMap<>();

        if ("not_reliable".equals(status)) {
            out.put("executive_summary", buildNotReliableSummary(targetName, dataQuality));
            out.put("main_forecast_sentence", "Forecast nebyl spočítán — data nesplňují minimální podmínky spolehlivosti.");
            out.put("drivers_sentence", "Bez forecastu nelze vyhodnotit drivery.");
            out.put("risk_sentence", "Doporučeno doplnit vstupní data před dalším pokusem o forecast.");
            out.put("methodology_note", "Guardrails zablokovaly forecast — viz data_quality.what_would_help.");
            out.put("article_headline", targetName + ": data zatím nestačí na modelový odhad");
            out.put("chart_caption", "Historická řada „" + targetName + "“ bez technického forecastu (nedostatek dat).");
            out.put("watch_next", dataQuality.getOrDefault("what_would_help", List.of()));
            return out;
        }

        String direction = changePct == null ? "stabilní" : changePct > 1.5 ? "růstový" : changePct < -1.5 ? "klesající" : "stabilní";
        String driversSentence = drivers.isEmpty()
                ? "Model staví převážně na autoregresní historii cílové řady — bez jednoznačně dominantních exogenních driverů."
                : "Model jako hlavní drivery vyhodnotil: " + String.join(", ", drivers) + ".";
        if (!topSelectedFeatureNames.isEmpty()) {
            // Deterministic formatting of already-computed JSON values (selected_features), per
            // the spec's example: "Z kandidátních vstupů prošly guardrails zejména {...}" — no
            // new numbers are computed here, only the discovered/validated input names are listed.
            driversSentence += " Z kandidátních vstupů prošly guardrails a backtestem zejména: " + String.join(", ", topSelectedFeatureNames) + ".";
        }

        String executiveSummary = String.format(
                Locale.US,
                "Technický forecast pro %s v horizontu %s ukazuje modelový odhad (baseline scénář) %s%s. "
                        + "Interval nejistoty je %s až %s%s. Použitý model: %s.",
                targetName, horizonLabel, fmt(p50), unit, fmt(p10), fmt(p90), unit, selectedModel);

        String mainForecastSentence = String.format(
                Locale.US,
                "Při daných předpokladech (baseline scénář) je modelový odhad %s%s v horizontu %s, což odpovídá změně o %s %% "
                        + "oproti poslední dostupné hodnotě (%s%s, %s).",
                fmt(p50), unit, horizonLabel, fmt(changePct), fmtWithUnit(lastValue, str(targetSeries.get("unit"))), unit, lastDate);

        String riskSentence = String.format(
                Locale.US,
                "Výsledek je citlivý na zvolené předpoklady a nejistota roste s horizontem — interval nejistoty (p10-p90) je %s až %s%s. "
                        + "Jde o technický odhad, nikoli jistou predikci.%s",
                fmt(p10), fmt(p90), unit, warningsSuffix(dataQuality));

        String methodologyNote = String.format(
                Locale.US,
                "Modelový odhad vychází z metody „%s“ (větev %s), vybrané na základě rolling-origin backtestu "
                        + "(MAE=%s, RMSE=%s%s).%s",
                selectedModel, branch, fmt(num(backtest.get("mae"))), fmt(num(backtest.get("rmse"))),
                backtest.get("smape") != null ? ", sMAPE=" + fmt(num(backtest.get("smape"))) + "%" : "",
                fallbackUsed ? " Použit fallback model (preferovaný přístup backtest nepřekonal baseline)." : "");

        String articleHeadline = String.format(
                Locale.US, "%s: modelový odhad ukazuje %s vývoj na %s%s do horizontu %s", targetName, direction, fmt(p50), unit, horizonLabel);

        String chartCaption = String.format(
                Locale.US,
                "Historická řada a technický forecast pro „%s“ — baseline scénář s pásmem nejistoty p10-p90 (%s).",
                targetName, domainLabelCz == null ? "modelový odhad" : domainLabelCz);

        List<String> watchNext = new ArrayList<>();
        for (String d : drivers) {
            watchNext.add("Vývoj: " + d);
        }
        watchNext.add("Nové oficiální statistiky pro „" + targetName + "“ (aktualizace vstupních dat forecastu)");

        out.put("executive_summary", executiveSummary);
        out.put("main_forecast_sentence", mainForecastSentence);
        out.put("drivers_sentence", driversSentence);
        out.put("risk_sentence", riskSentence);
        out.put("methodology_note", methodologyNote);
        out.put("article_headline", articleHeadline);
        out.put("chart_caption", chartCaption);
        out.put("watch_next", watchNext);
        return out;
    }

    /**
     * Top 2-3 {@code selected_features} (by {@code backtest_contribution}, already computed by
     * the Java engine's feature-discovery layer), deduplicated by economic concept for a
     * readable Czech sentence — this is purely re-formatting of existing JSON, no new numbers.
     */
    @SuppressWarnings("unchecked")
    private static List<String> topSelectedFeatureNames(Map<String, Object> forecastResponse) {
        List<Map<String, Object>> selectedFeatures = forecastResponse.get("selected_features") instanceof List<?> list
                ? (List<Map<String, Object>>) list
                : List.of();
        return selectedFeatures.stream()
                .sorted((a, b) -> Double.compare(contributionOrZero(b), contributionOrZero(a)))
                .map(f -> {
                    String concept = str(f.get("concept"));
                    return !concept.isBlank() ? concept : str(f.get("feature_name"));
                })
                .filter(s -> !s.isBlank())
                .distinct()
                .limit(3)
                .toList();
    }

    private static double contributionOrZero(Map<String, Object> feature) {
        Double contribution = num(feature.get("backtest_contribution"));
        return contribution == null ? 0.0 : contribution;
    }

    private static String buildNotReliableSummary(String targetName, Map<String, Object> dataQuality) {
        Object reasons = dataQuality.get("warnings");
        String reasonText = reasons instanceof List<?> list && !list.isEmpty() ? String.valueOf(list.get(0)) : "nedostatek dat";
        return "Pro „" + targetName + "“ nelze v tuto chvíli vytvořit spolehlivý technický forecast (" + reasonText + ").";
    }

    private static String warningsSuffix(Map<String, Object> dataQuality) {
        Object warnings = dataQuality.get("warnings");
        if (warnings instanceof List<?> list && !list.isEmpty()) {
            return " Poznámka ke kvalitě dat: " + list.get(0) + (list.size() > 1 ? " (a další)." : ".");
        }
        return "";
    }

    private static String unitSuffix(String unit) {
        if (unit == null || unit.isBlank()) {
            return "";
        }
        return " " + unit;
    }

    private static String fmt(Double value) {
        if (value == null) {
            return "n/a";
        }
        if (Math.abs(value) >= 1e6) {
            return AnalyticsValueFormatter.formatCompact(value);
        }
        return String.format(Locale.US, "%.2f", value);
    }

    private static String fmtWithUnit(Double value, String unit) {
        if (value == null) {
            return "n/a";
        }
        return AnalyticsValueFormatter.formatValue(value, unit);
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
