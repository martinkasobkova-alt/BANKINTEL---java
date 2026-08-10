package cz.bankintel.search;

import cz.bankintel.domain.entity.ComputedIndicatorEntity;
import cz.bankintel.service.calculations.ComputedIndicatorRunner;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Handles non-search follow-up actions — ref {@code catalog_followup_service.py}. */
@Component
public class CatalogFollowupActionHandler {

    private final ComputedIndicatorRunner computedRunner;

    public CatalogFollowupActionHandler(ComputedIndicatorRunner computedRunner) {
        this.computedRunner = computedRunner;
    }

    public FollowupActionResult handle(
            String action, String message, List<Map<String, Object>> selectedRefs, String userId) {
        return switch (action) {
            case "explain_indicator" -> explainIndicator(selectedRefs);
            case "compute_ratio" -> computeBinary(selectedRefs, userId, "ratio", "poměr");
            case "compute_difference" -> computeBinary(selectedRefs, userId, "diff", "rozdíl");
            case "compute_percent_change" -> computeBinary(selectedRefs, userId, "pct", "procentní změnu");
            case "compose_multi_chart", "compare_selected", "add_to_chart" ->
                    composeMulti(selectedRefs, userId);
            default -> null;
        };
    }

    private FollowupActionResult explainIndicator(List<Map<String, Object>> refs) {
        if (refs == null || refs.isEmpty()) {
            return FollowupActionResult.chatOnly(
                    "Pro vysvětlení indikátoru vyberte aspoň jednu řadu. "
                            + "Pak vysvětlím, co ukazatel měří, jak se počítá a jaká má omezení.");
        }
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> ref : refs.stream().limit(3).toList()) {
            String title = String.valueOf(ref.getOrDefault("title", ref.getOrDefault("set_id", "indikátor")))
                    .trim();
            String lower = title.toLowerCase(Locale.ROOT);
            if (lower.contains("hicp") || lower.contains("spotrebitel") || lower.contains("inflac")) {
                lines.add(
                        "„" + title + "“: Měří změnu cen spotřebního koše. "
                                + "Vyšší hodnota = rychlejší růst cen; sledujte periodu a srovnatelný základ.");
            } else if (lower.contains("ppi") || lower.contains("vyrobc")) {
                lines.add(
                        "„" + title + "“: Ceny u výrobců — tlak v produkčním řetězci, "
                                + "nepřenáší se do spotřebitelské inflace 1:1.");
            } else if (lower.contains("nezamest")) {
                lines.add(
                        "„" + title + "“: Podíl lidí bez práce; interpretace závisí na metodice a věkové skupině.");
            } else {
                lines.add(
                        "„" + title + "“: Čtěte definici jednotky, periodu a geografického pokrytí před srovnáním.");
            }
        }
        return FollowupActionResult.chatOnly(String.join("\n\n", lines));
    }

    private FollowupActionResult computeBinary(
            List<Map<String, Object>> refs, String userId, String operation, String labelCz) {
        if (refs == null || refs.size() < 2) {
            return FollowupActionResult.chatOnly(
                    "Pro výpočet " + labelCz + " vyberte alespoň dvě řady z výsledků hledání.");
        }
        Map<String, Object> leftRef = operandRef(refs.get(0));
        Map<String, Object> rightRef = operandRef(refs.get(1));
        ComputedIndicatorEntity doc = new ComputedIndicatorEntity();
        doc.setOperation(operation);
        doc.setLeft(leftRef);
        doc.setRight(rightRef);
        ComputedIndicatorRunner.RunResult result = computedRunner.run(doc, userId);
        if (result.rows().isEmpty()) {
            String warn = result.warnings().isEmpty() ? "Nepodařilo se načíst data řad." : result.warnings().getFirst();
            return FollowupActionResult.chatOnly("Výpočet " + labelCz + " selhal: " + warn);
        }
        Map<String, Object> last = result.rows().getLast();
        Object value = last.get("value");
        String period = String.valueOf(last.getOrDefault("period", ""));
        String answer = "Výpočet " + labelCz + " (poslední období " + period + "): " + value;
        Map<String, Object> computation = new LinkedHashMap<>();
        computation.put("operation", operation);
        computation.put("rows", result.rows());
        computation.put("warnings", result.warnings());
        computation.put("diagnostics", result.diagnostics());
        return new FollowupActionResult(answer, computation);
    }

    private FollowupActionResult composeMulti(List<Map<String, Object>> refs, String userId) {
        if (refs == null || refs.isEmpty()) {
            return FollowupActionResult.chatOnly("Vyberte alespoň jednu řadu pro sestavení grafu.");
        }
        ComputedIndicatorEntity doc = new ComputedIndicatorEntity();
        doc.setOperation("multi");
        List<Map<String, Object>> series = new ArrayList<>();
        for (int i = 0; i < Math.min(refs.size(), 5); i++) {
            Map<String, Object> ref = refs.get(i);
            Map<String, Object> s = new LinkedHashMap<>(operandRef(ref));
            s.put("key", "s" + (i + 1));
            s.put("name", ref.getOrDefault("title", ref.get("set_id")));
            series.add(s);
        }
        doc.setSeries(series);
        ComputedIndicatorRunner.RunResult result = computedRunner.run(doc, userId);
        if (result.rows().isEmpty()) {
            return FollowupActionResult.chatOnly(
                    result.warnings().isEmpty()
                            ? "Graf se nepodařilo sestavit — chybí data řad."
                            : result.warnings().getFirst());
        }
        Map<String, Object> computation = new LinkedHashMap<>();
        computation.put("operation", "multi");
        computation.put("rows", result.rows());
        computation.put("diagnostics", result.diagnostics());
        computation.put("chart_payload", chartPayload(series, result.rows()));
        return new FollowupActionResult(
                "Sestaven multi-graf z " + series.size() + " řad (" + result.rows().size() + " období).",
                computation);
    }

    private static Map<String, Object> chartPayload(
            List<Map<String, Object>> sourceSeries, List<Map<String, Object>> rows) {
        List<Map<String, Object>> chartSeries = new ArrayList<>();
        Double primaryMagnitude = sourceSeries.isEmpty()
                ? null
                : medianMagnitude(rows, String.valueOf(sourceSeries.getFirst().get("key")));
        List<String> titles = new ArrayList<>();
        for (Map<String, Object> source : sourceSeries) {
            String key = String.valueOf(source.getOrDefault("key", "")).trim();
            String title = String.valueOf(source.getOrDefault("name", source.getOrDefault("set_id", key))).trim();
            if (!title.isBlank()) {
                titles.add(title);
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("key", key);
            meta.put("name", title);
            meta.put("label", title);
            meta.put("chart_type", "line");
            Double magnitude = medianMagnitude(rows, key);
            if (primaryMagnitude != null
                    && magnitude != null
                    && primaryMagnitude > 0
                    && magnitude > 0
                    && (magnitude / primaryMagnitude > 4 || primaryMagnitude / magnitude > 4)) {
                meta.put("y_axis", "right");
            } else {
                meta.put("y_axis", "left");
            }
            chartSeries.add(meta);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", titles.isEmpty() ? "Slozeny graf" : String.join(" vs ", titles));
        payload.put("multi_series", true);
        payload.put("chart_data_mode", "series");
        payload.put("chart_type", "line");
        payload.put("group_field", "series");
        payload.put("rows", rows);
        payload.put("series", chartSeries);
        return payload;
    }

    private static Double medianMagnitude(List<Map<String, Object>> rows, String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        List<Double> values = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object raw = row.get(key);
            if (raw instanceof Number number) {
                double value = Math.abs(number.doubleValue());
                if (Double.isFinite(value) && value > 0) {
                    values.add(value);
                }
            }
        }
        if (values.isEmpty()) {
            return null;
        }
        values.sort(Double::compareTo);
        return values.get(values.size() / 2);
    }

    private static Map<String, Object> operandRef(Map<String, Object> ref) {
        Map<String, Object> out = new LinkedHashMap<>(ref);
        String sourceType = firstNonBlank(ref, "source_type", "catalog_id", "source");
        String sourceId = firstNonBlank(ref, "source_id");
        String setId = String.valueOf(ref.getOrDefault("set_id", ref.getOrDefault("series_id", ""))).trim();
        if (!sourceType.isBlank()) {
            out.put("source_type", sourceType);
        }
        if (sourceId.isBlank() && !sourceType.isBlank()) {
            sourceId = sourceType;
        }
        out.put("source_id", sourceId);
        out.put("indicator_id", setId);
        out.put("set_id", setId);
        return out;
    }

    private static String firstNonBlank(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            Object raw = values.get(key);
            String value = raw == null ? "" : String.valueOf(raw).trim();
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    public record FollowupActionResult(String assistantAnswer, Map<String, Object> computationResult) {
        static FollowupActionResult chatOnly(String answer) {
            return new FollowupActionResult(answer, null);
        }
    }
}
