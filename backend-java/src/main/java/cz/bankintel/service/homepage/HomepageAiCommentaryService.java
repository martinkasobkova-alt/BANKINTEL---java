package cz.bankintel.service.homepage;
import cz.bankintel.util.BankIntelEnvVars;

import com.fasterxml.jackson.databind.JsonNode;
import cz.bankintel.search.openai.OpenAiClient;
import cz.bankintel.service.userdata.UserDataParseService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Volitelné AI komentáře k widgetům — obdoba Python {@code services/ai_chart_commentary.py}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HomepageAiCommentaryService {

    private static final Set<String> DISABLED_FLAGS = Set.of("0", "false", "no");
    private static final Set<String> CHART_WIDGET_TYPES = Set.of(
            "chart",
            "dataset_chart",
            "external_catalog_chart",
            "formula_chart",
            "arad_view",
            "computed_view",
            "dataset_view",
            "eurostat_view",
            "csu_view",
            "ecb_view",
            "fred_view",
            "alphavantage_view",
            "worldbank_view",
            "world_bank_data360_view",
            "bis_view",
            "imf_view",
            "oecd_view");

    private final OpenAiClient openAiClient;

    public boolean commentaryEnabled() {
        String flag = BankIntelEnvVars.get("OPENAI_COMMENTARY");
        if (flag != null && DISABLED_FLAGS.contains(flag.trim().toLowerCase(Locale.ROOT))) {
            return false;
        }
        String key = BankIntelEnvVars.get("OPENAI_API_KEY");
        return key != null && !key.isBlank();
    }

    public Map<String, Object> generateVerbose(
            String widgetType, String title, Map<String, Object> data, String extraInstruction, Map<String, Object> widgetConfig) {
        if (data != null && data.get("error") != null) {
            return Map.of(
                    "text",
                    null,
                    "reason",
                    "Widget vrací chybu dat: " + String.valueOf(data.get("error")).substring(0, Math.min(200, String.valueOf(data.get("error")).length())),
                    "summary",
                    null,
                    "fallback_used",
                    false);
        }
        String summary = buildDataSummary(widgetType, data);
        String meta = extractMeta(data);
        boolean fallback = false;
        String text = null;
        String reason = null;
        if (commentaryEnabled() && summary != null) {
            try {
                text = callOpenAi(title, widgetType, summary, meta, extraInstruction);
            } catch (Exception ex) {
                reason = ex.getMessage();
                log.warn("OpenAI commentary failed: {}", ex.getMessage());
            }
        }
        if (text == null || text.isBlank()) {
            String deterministic = buildDeterministicCommentary(data, title);
            if (!deterministic.isBlank()) {
                text = deterministic;
                fallback = true;
            }
        }
        if ((text == null || text.isBlank()) && !commentaryEnabled()) {
            reason = "OPENAI_API_KEY není nastaven nebo je OPENAI_COMMENTARY vypnuté.";
        } else if ((text == null || text.isBlank()) && summary == null) {
            reason = "Pro tento typ widgetu / zvolená data nelze sestavit podklady pro AI interpretaci.";
        } else if ((text == null || text.isBlank()) && reason == null) {
            reason = "OpenAI vrátil prázdnou odpověď.";
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", text);
        out.put("reason", text != null ? null : reason);
        out.put("summary", summary);
        out.put("fallback_used", fallback);
        return out;
    }

    public void attachBatch(List<Map<String, Object>> widgets, Double maxWaitSec) {
        if (!commentaryEnabled() || widgets == null || widgets.isEmpty()) {
            return;
        }
        List<Map<String, Object>> targets = widgets.stream()
                .filter(w -> w != null && w.get("type") != null)
                .toList();
        if (targets.isEmpty()) {
            return;
        }
        if (targets.size() == 1) {
            attachOne(targets.getFirst());
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(4, targets.size()));
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Map<String, Object> widget : targets) {
                futures.add(pool.submit(() -> attachOne(widget)));
            }
            long timeoutMs = maxWaitSec != null ? (long) (maxWaitSec * 1000) : Long.MAX_VALUE;
            long deadline = System.currentTimeMillis() + timeoutMs;
            for (Future<?> future : futures) {
                long wait = Math.max(1, deadline - System.currentTimeMillis());
                try {
                    future.get(wait, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ex) {
                    log.info("AI commentary batch timed out after {}s", maxWaitSec);
                    futures.forEach(f -> f.cancel(true));
                    break;
                } catch (Exception ex) {
                    log.warn("Commentary task failed: {}", ex.getMessage());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @SuppressWarnings("unchecked")
    private void attachOne(Map<String, Object> widget) {
        if (widget.get("ai_commentary") != null && widget.get("ai_analysis_payload") != null) {
            return;
        }
        Object dataObj = widget.get("data");
        if (dataObj instanceof Map<?, ?> dataMap && dataMap.get("error") != null) {
            return;
        }
        Map<String, Object> data = dataObj instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Map<String, Object> config =
                widget.get("config") instanceof Map<?, ?> cfg ? (Map<String, Object>) cfg : Map.of();
        Map<String, Object> verbose = generateVerbose(
                String.valueOf(widget.get("type")),
                String.valueOf(widget.getOrDefault("title", "")),
                data,
                "",
                config);
        Object text = verbose.get("text");
        if (text instanceof String s && !s.isBlank()) {
            widget.put("ai_commentary", s);
        }
        widget.put("ai_commentary_fallback", Boolean.TRUE.equals(verbose.get("fallback_used")));
    }

    private String callOpenAi(String title, String widgetType, String summary, String meta, String extraInstruction)
            throws Exception {
        String system =
                "Jsi ekonomický analytik. Piš stručně v češtině (max 120 slov). "
                        + "Popisuj jen data ze shrnutí — nevymýšlej příčiny ani čísla. "
                        + "NIKDY nepiš slovo widget. Začni „Graf ukazuje…“ nebo „Ukazatel…“.";
        StringBuilder user = new StringBuilder();
        user.append("Název ukazatele: ").append(title).append('\n');
        user.append("Typ vizualizace: ").append(widgetType).append('\n');
        if (!meta.isBlank()) {
            user.append("Metadata: ").append(meta).append('\n');
        }
        String extra = sanitizePrompt(extraInstruction);
        if (!extra.isBlank()) {
            user.append("Doplňující instrukce od admina: ").append(extra).append('\n');
        }
        user.append("Data (souhrn čísel): ").append(summary).append('\n');
        user.append("Sestav komentář:");
        JsonNode response = openAiClient.chatCompletion(system, user.toString());
        JsonNode content = response.path("choices").path(0).path("message").path("content");
        if (content.isTextual() && !content.asText().isBlank()) {
            return cleanCommentary(content.asText());
        }
        return null;
    }

    private static String buildDeterministicCommentary(Map<String, Object> data, String title) {
        if (data == null || data.isEmpty()) {
            return "";
        }
        List<Map<String, Object>> rows = rowsFromData(data);
        Map<String, Object> stats = computeSeriesStats(rows);
        if (stats.isEmpty()) {
            return "";
        }
        String datasetName = stringOr(data.get("dataset"), title);
        String unit = stringOr(data.get("unit"), "");
        String unitSuffix = unit.isBlank() ? "" : " " + unit;
        return String.format(
                Locale.ROOT,
                "Použitá řada „%s“. Poslední hodnota je %s%s v období %s. "
                        + "Maximum %s%s (%s), minimum %s%s (%s). Celková změna mezi prvním a posledním bodem: %s%s.",
                datasetName,
                fmtNum(stats.get("last_value")),
                unitSuffix,
                stats.get("last_date"),
                fmtNum(stats.get("max_value")),
                unitSuffix,
                stats.get("max_date"),
                fmtNum(stats.get("min_value")),
                unitSuffix,
                stats.get("min_date"),
                fmtNum(stats.get("absolute_change")),
                unitSuffix);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rowsFromData(Map<String, Object> data) {
        Object rowsObj = data.get("rows");
        if (!(rowsObj instanceof List<?> rows)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object row : rows) {
            if (row instanceof Map<?, ?> map) {
                out.add((Map<String, Object>) map);
            }
        }
        return out;
    }

    private static Map<String, Object> computeSeriesStats(List<Map<String, Object>> rows) {
        List<Map<String, String>> nums = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String date = firstNonBlank(row, "date", "period", "x");
            Double val = parseY(row);
            if (date != null && val != null) {
                nums.add(Map.of("date", date, "value", String.valueOf(val)));
            }
        }
        if (nums.size() < 2) {
            return Map.of();
        }
        double first = Double.parseDouble(nums.getFirst().get("value"));
        double last = Double.parseDouble(nums.getLast().get("value"));
        double min = first;
        double max = first;
        String minDate = nums.getFirst().get("date");
        String maxDate = nums.getFirst().get("date");
        for (Map<String, String> point : nums) {
            double val = Double.parseDouble(point.get("value"));
            if (val < min) {
                min = val;
                minDate = point.get("date");
            }
            if (val > max) {
                max = val;
                maxDate = point.get("date");
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("first_value", first);
        out.put("first_date", nums.getFirst().get("date"));
        out.put("last_value", last);
        out.put("last_date", nums.getLast().get("date"));
        out.put("min_value", min);
        out.put("min_date", minDate);
        out.put("max_value", max);
        out.put("max_date", maxDate);
        out.put("absolute_change", last - first);
        return out;
    }

    private static String buildDataSummary(String widgetType, Map<String, Object> data) {
        if (data == null || data.get("error") != null) {
            return null;
        }
        String wt = widgetType != null ? widgetType : "";
        if ("chart_net_result".equals(wt)) {
            return summarizeNumericRows(rowsFromData(data));
        }
        if (CHART_WIDGET_TYPES.contains(wt)) {
            if ("table".equals(String.valueOf(data.get("view")))) {
                return summarizeTableRows(rowsFromData(data));
            }
            String numeric = summarizeNumericRows(rowsFromData(data));
            return numeric != null ? numeric : summarizeTableRows(rowsFromData(data));
        }
        if (wt.startsWith("kpi_")) {
            return String.format(
                    Locale.ROOT,
                    "Jedno číslo KPI: %s, poznámka: %s, trend: %s.",
                    data.get("value"),
                    data.getOrDefault("hint", ""),
                    data.getOrDefault("trend", "—"));
        }
        return null;
    }

    private static String summarizeNumericRows(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        List<String> dates = new ArrayList<>();
        List<Double> vals = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String x = firstNonBlank(row, "x", "date", "period", "key");
            Double y = parseY(row);
            if (x != null && y != null) {
                dates.add(x);
                vals.add(y);
            }
        }
        if (vals.size() < 2) {
            return null;
        }
        double first = vals.getFirst();
        double last = vals.getLast();
        double min = vals.stream().min(Double::compare).orElse(first);
        double max = vals.stream().max(Double::compare).orElse(first);
        double delta = last - first;
        String trend = Math.abs(delta) < 1e-9 * Math.max(1, Math.abs(last))
                ? "stabilní"
                : (delta > 0 ? "rostoucí" : "klesající");
        return String.format(
                Locale.ROOT,
                "Počet pozorování: %d. Minimum %s, maximum %s. "
                        + "První období %s hodnota %s; poslední období %s hodnota %s. "
                        + "Absolutní změna: %s (trend: %s).",
                vals.size(),
                fmtNum(min),
                fmtNum(max),
                dates.getFirst(),
                fmtNum(first),
                dates.getLast(),
                fmtNum(last),
                fmtNum(delta),
                trend);
    }

    private static String summarizeTableRows(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        List<String> fields = new ArrayList<>(rows.getFirst().keySet());
        return String.format(
                Locale.ROOT,
                "Tabulka má %d řádků a %d sloupců. Sloupce: %s.",
                rows.size(),
                fields.size(),
                String.join(", ", fields.stream().limit(12).toList()));
    }

    private static String extractMeta(Map<String, Object> data) {
        if (data == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        putMeta(parts, "zobrazení", data.get("view"));
        putMeta(parts, "jednotka", data.get("unit"));
        putMeta(parts, "frekvence", data.get("frequency"));
        putMeta(parts, "datová sada", data.get("dataset"));
        return String.join("; ", parts);
    }

    private static void putMeta(List<String> parts, String label, Object value) {
        String s = stringOr(value, "");
        if (!s.isBlank()) {
            parts.add(label + ": " + s);
        }
    }

    private static Double parseY(Map<String, Object> row) {
        for (String key : List.of("y", "result", "value")) {
            Double val = UserDataParseService.parseNumber(row.get(key));
            if (val != null) {
                return val;
            }
        }
        return null;
    }

    private static String firstNonBlank(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            Object val = row.get(key);
            if (val != null && !String.valueOf(val).isBlank()) {
                return String.valueOf(val);
            }
        }
        return null;
    }

    private static String sanitizePrompt(String value) {
        String raw = value != null ? value.trim() : "";
        if (raw.length() > 600) {
            raw = raw.substring(0, 600).strip() + "…";
        }
        return raw.replace("\r", " ").replace("\u0000", " ");
    }

    private static String cleanCommentary(String text) {
        String out = text != null ? text.strip() : "";
        out = out.replaceAll("(?i)widget", "graf");
        return out.replaceAll("\\s{2,}", " ").strip();
    }

    private static String fmtNum(Object value) {
        if (value == null) {
            return "neuvedeno";
        }
        if (value instanceof Number n) {
            String txt = String.format(Locale.ROOT, "%.2f", n.doubleValue()).replaceAll("0+$", "").replaceAll("\\.$", "");
            return txt.replace('.', ',');
        }
        return String.valueOf(value);
    }

    private static String stringOr(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String s = String.valueOf(value).trim();
        return s.isBlank() ? fallback : s;
    }
}
