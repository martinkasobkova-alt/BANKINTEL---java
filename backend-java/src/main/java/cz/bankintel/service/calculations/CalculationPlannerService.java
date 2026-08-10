package cz.bankintel.service.calculations;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class CalculationPlannerService {

    public Map<String, Object> planCalculation(String question) {
        String q = question == null ? "" : question.strip();
        if (q.isBlank()) {
            return Map.of(
                    "intent", "unknown",
                    "warnings", List.of("Prázdný dotaz"),
                    "required_series", List.of(),
                    "normalization", Map.of());
        }
        String ql = q.toLowerCase();
        String detected = null;
        if (Pattern.compile("\\broa\\b|zisk.*?aktiva|aktiva.*?zisk", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                .matcher(ql)
                .find()) {
            detected = "calculate_roa";
        } else if (Pattern.compile("\\broe\\b|zisk.*?kapit[aá]l", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                .matcher(ql)
                .find()) {
            detected = "calculate_roe";
        } else if (Pattern.compile("npl", Pattern.CASE_INSENSITIVE).matcher(ql).find()) {
            detected = "calculate_npl_ratio";
        } else if (Pattern.compile("úv[eě]ry.*?vklad|loan.*?deposit", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                .matcher(ql)
                .find()) {
            detected = "calculate_ldr";
        } else if (Pattern.compile("korelac", Pattern.CASE_INSENSITIVE).matcher(ql).find()) {
            detected = "correlation";
        } else if (Pattern.compile("progn[oózu|ostic]|forecast", Pattern.CASE_INSENSITIVE).matcher(ql).find()) {
            detected = "forecast";
        } else if (Pattern.compile("yoy|meziro[čc]", Pattern.CASE_INSENSITIVE).matcher(ql).find()) {
            detected = "calculate_yoy";
        } else if (Pattern.compile("index.*?100|na 100 v roce", Pattern.CASE_INSENSITIVE).matcher(ql).find()) {
            detected = "index_100_normalize";
        } else if (Pattern.compile("sou[čc]et|plus|\\+\\s*B", Pattern.CASE_INSENSITIVE).matcher(ql).find()) {
            detected = "sum_series";
        } else if (Pattern.compile("pom[eě]r|ratio|\\s/\\s*|÷", Pattern.CASE_INSENSITIVE).matcher(ql).find()) {
            detected = "ratio_calc";
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("intent", detected != null ? detected : "generic_calculation");
        out.put("question_original", question);
        out.put("required_series", new ArrayList<>());
        out.put("suggested_operation", null);
        List<String> warnings = new ArrayList<>();
        List<String> confirmations = new ArrayList<>();
        out.put(
                "normalization",
                Map.of(
                        "scale_alignment", true,
                        "currency_alignment", "user_choice_no_silent_conversion",
                        "frequency_alignment", "overlap_or_user_choice_in_MVP"));
        out.put("warnings", warnings);
        out.put("user_confirmations_needed", confirmations);
        out.put(
                "mvp_note_cs",
                "Tato vrstva MVP vrací pouze strukturovaný návrh. Samotný výpočet se provádí "
                        + "pouze endpointem `/api/calculations/compute` v deterministickém motoru.");

        if (ql.contains("ratio") || q.contains("/") || q.contains("÷") || ql.contains("poměr")) {
            out.put("suggested_operation", ql.contains("%") || ql.contains("proc") ? "ratio_percent" : "ratio");
            out.put("required_series", List.of("series_a", "series_b"));
        } else if (ql.contains("correl")) {
            out.put("suggested_operation", "pearson_correlation");
            warnings.add("Vyžaduje sjednocení frekvence a společné období — ručně v builderu.");
        } else if (Pattern.compile("roa|zisk.*?aktiva", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                .matcher(ql)
                .find()) {
            out.put("intent", "calculate_roa");
            out.put("suggested_operation", "ratio_pct");
            out.put("required_series", List.of("profit_flow", "assets_stock_quarter_end"));
            warnings.add("ROA používá zisk jako tok — potvrďte, zda používáte kvartální či kumulovaný výsledek.");
            confirmations.add("Kvartální zisk versus kumulovaný k mezisu?");
        } else if (Pattern.compile("roe", Pattern.CASE_INSENSITIVE).matcher(ql).find()) {
            out.put("intent", "calculate_roe");
            out.put("required_series", List.of("profit_flow", "equity_stock"));
            out.put("suggested_operation", "ratio_pct");
        }
        return out;
    }
}
