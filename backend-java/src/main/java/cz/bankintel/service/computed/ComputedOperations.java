package cz.bankintel.service.computed;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

public final class ComputedOperations {

    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put("ratio", "podíl A ÷ B");
        LABELS.put("sum", "součet A + B");
        LABELS.put("diff", "rozdíl A − B");
        LABELS.put("mult", "součin A × B");
        LABELS.put("pct", "procento (A ÷ B) × 100");
        LABELS.put("multi", "složený graf (více řad)");
        LABELS.put("pct_points", "Rozdíl v procentních bodech (A − B)");
        LABELS.put("log_a", "Přirozený logaritmus řady A");
        LABELS.put("index_100_first", "Index báze 100 v prvním období řady A");
        LABELS.put("index_b100_first", "Index báze 100 v prvním období řady B");
        LABELS.put("yoy_pct_auto", "YoY % (automatický meziroční krok)");
        LABELS.put("yoy_abs_auto", "YoY absolutní změna řady A");
        LABELS.put("mom_pct_auto", "Posun vs. předchozí dostupný krok (%)");
        LABELS.put("qoq_pct_auto", "Posun vs. předchozí čtvrtletí (odhad z posloupnosti období)");
        LABELS.put("roll_mean", "Klouzavý průměr (okno v options.window)");
        LABELS.put("cumsum", "Kumulativní součet řady A");
        LABELS.put("volatility_ret", "Směrodatná odchylka mezibodových relativních změn");
        LABELS.put("zscore", "Z-skóre hodnot řady A");
        LABELS.put("drawdown_pct", "Drawdown od maxima (%)");
        LABELS.put("cagr_range", "CAGR mezi prvním a posledním bodem (hrubý odhad období)");
        LABELS.put("corr_pearson", "Pearsonova korelace A vs B");
        LABELS.put("regress_ols", "OLS regrese A ~ B na průniku období");
        LABELS.put("index_vs_b_pct", "(A ÷ B) × 100");
        LABELS.put("real_div_infl", "A / B × 100 (deflátor — ověřte kompatibilitu jednotek)");
        LABELS.put("cum_change_first", "Kumulativní změna od prvního pozorovaného bodu řady A");
        LABELS.put("pct_rank_hist", "Percentilové pořadí hodnoty v celé historii řady A");
        LABELS.put("trend_linear_time", "Lineární trend podle řazené časové osy řady A");
    }

    private ComputedOperations() {}

    public static Map<String, String> allLabels() {
        return LABELS;
    }

    public static String labelFor(String operation) {
        return LABELS.getOrDefault(operation, operation);
    }

    public static java.util.List<Map<String, String>> sortedOperations() {
        TreeMap<String, String> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        sorted.putAll(LABELS);
        return sorted.entrySet().stream()
                .map(e -> Map.of("value", e.getKey(), "label", e.getValue()))
                .toList();
    }
}
