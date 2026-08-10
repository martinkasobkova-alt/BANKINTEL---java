package cz.bankintel.service.userdata;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class UserSeriesMapper {

    private static final Pattern WS = Pattern.compile("\\s+");

    private record MetricRule(String metricType, String domain, List<String> terms) {}

    private static final List<MetricRule> RULES = List.of(
            new MetricRule("financial_revenue", "company_performance", List.of("revenue", "trzby", "vynosy", "obrat", "sales")),
            new MetricRule("financial_costs", "company_costs", List.of("costs", "naklady", "expenses", "spotreba")),
            new MetricRule("financial_margin", "company_profitability", List.of("margin", "marze")),
            new MetricRule("financial_profit", "company_profitability", List.of("profit", "zisk", "ebitda", "ebit")),
            new MetricRule("production_volume", "company_production", List.of("production", "vyroba", "output", "volume")),
            new MetricRule("inventory", "company_inventory", List.of("inventory", "zasoby", "stock")),
            new MetricRule("orders", "company_demand", List.of("orders", "objednavky")),
            new MetricRule("employment", "company_labour", List.of("employment", "zamestnanci", "headcount")),
            new MetricRule("other", "company_performance", List.of()));

    private UserSeriesMapper() {}

    public static Map<String, Object> classifyMetric(String title, String description, List<String> tags) {
        String haystack = fold(title) + " " + fold(description) + " " + fold(String.join(" ", tags == null ? List.of() : tags));
        MetricRule best = RULES.getLast();
        int bestScore = 0;
        for (MetricRule rule : RULES) {
            if (rule.terms().isEmpty()) {
                continue;
            }
            int score = 0;
            for (String term : rule.terms()) {
                if (haystack.contains(fold(term))) {
                    score += term.length();
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = rule;
            }
        }
        double confidence = bestScore > 0 ? Math.min(0.95, 0.55 + bestScore * 0.02) : 0.35;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("metric_type", best.metricType());
        out.put("detected_domain", best.domain());
        out.put("confidence", confidence);
        out.put("mapping_reason", bestScore > 0 ? "keyword_match" : "default_other");
        return out;
    }

    public static Map<String, Object> overrideMetricMapping(String metricType) {
        String mt = metricType == null ? "other" : metricType.strip().toLowerCase(Locale.ROOT);
        for (MetricRule rule : RULES) {
            if (rule.metricType().equals(mt)) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("metric_type", rule.metricType());
                out.put("detected_domain", rule.domain());
                out.put("confidence", 0.99);
                out.put("mapping_reason", "manual_override");
                return out;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("metric_type", mt.isBlank() ? "other" : mt);
        out.put("detected_domain", "company_performance");
        out.put("confidence", 0.99);
        out.put("mapping_reason", "manual_override");
        return out;
    }

    public static String detectFrequency(List<String> periods) {
        if (periods == null || periods.isEmpty()) {
            return "unknown";
        }
        int quarterly = 0;
        int monthly = 0;
        int yearly = 0;
        for (String p : periods) {
            String raw = p == null ? "" : p.strip().toUpperCase(Locale.ROOT);
            if (raw.matches("\\d{4}-Q[1-4]")) {
                quarterly++;
            } else if (raw.matches("\\d{4}-\\d{2}")) {
                monthly++;
            } else if (raw.matches("\\d{4}")) {
                yearly++;
            }
        }
        if (quarterly >= monthly && quarterly >= yearly && quarterly > 0) {
            return "quarterly";
        }
        if (monthly >= yearly && monthly > 0) {
            return "monthly";
        }
        if (yearly > 0) {
            return "yearly";
        }
        return "unknown";
    }

    private static String fold(String text) {
        return WS.matcher(text == null ? "" : text.strip().toLowerCase(Locale.ROOT)).replaceAll(" ");
    }
}
