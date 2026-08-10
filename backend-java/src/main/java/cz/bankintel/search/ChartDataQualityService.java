package cz.bankintel.search;
import cz.bankintel.util.BankIntelEnvVars;

import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Chart data freshness warnings — port {@code chart_series_data_quality.py}. */
@Service
public class ChartDataQualityService {

    private static final Pattern YEAR_RE = Pattern.compile("(?:19|20)\\d{2}");

    public Map<String, Object> assess(Map<String, Object> body) {
        Map<String, Object> meta = normalizeMeta(body);
        String lastPeriod = stringOrBlank(meta.get("last_period"));
        if (lastPeriod.isBlank()) {
            lastPeriod = stringOrBlank(meta.get("last_date"));
        }
        int minYear = freshnessMinYear();
        Integer lastYear = extractYear(lastPeriod);
        boolean stale = lastYear != null && lastYear < minYear;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stale", stale);
        out.put("last_period", lastPeriod.isBlank() ? null : lastPeriod);
        out.put("last_year", lastYear);
        out.put("freshness_min_year", minYear);
        out.put("warnings", stale ? buildWarnings(meta, lastPeriod) : List.of());
        out.put("suggested_search_queries", stale ? suggestedQueries(meta) : List.of());
        out.put("suggested_questions", stale ? suggestedQuestions(meta) : List.of());
        return out;
    }

    private static List<Map<String, Object>> buildWarnings(Map<String, Object> meta, String lastPeriod) {
        List<Map<String, Object>> warnings = new ArrayList<>();
        Map<String, Object> hint = knownSeriesHint(meta, lastPeriod);
        if (hint != null) {
            warnings.add(hint);
            return warnings;
        }
        warnings.add(Map.of(
                "reason_code", "data_ends_before_freshness_floor",
                "detail_cs",
                        "Poslední období v grafu (" + lastPeriod + ") je starší než očekávaná aktuálnost ("
                                + freshnessMinYear() + "+). Zkuste novější zdroj nebo alternativní ukazatel.",
                "severity", "warning"));
        return warnings;
    }

    private static Map<String, Object> knownSeriesHint(Map<String, Object> meta, String lastPeriod) {
        String blob = metaBlob(meta);
        String sid = stringOrBlank(meta.get("set_id")).toUpperCase();
        if (blob.contains("mfs_ir") || sid.contains("MFS162")) {
            return Map.of(
                    "reason_code", "imf_mfs_lending_rate_stale",
                    "detail_cs",
                            "Jde o harmonizovanou bankovní úvěrovou sazbu z IMF (MFS_IR), ne o sazbu centrální banky. "
                                    + "IMF tuto řadu u řady zemí neaktualizuje po roce 2017.",
                    "severity", "info",
                    "last_period", lastPeriod);
        }
        return null;
    }

    private static List<String> suggestedQueries(Map<String, Object> meta) {
        String country = stringOrBlank(meta.get("country_label"));
        if (country.isBlank()) {
            country = stringOrBlank(meta.get("geo_label"));
        }
        List<String> out = new ArrayList<>();
        if (!country.isBlank()) {
            out.add("policy rate " + country);
        }
        out.add("central bank policy rate");
        return out;
    }

    private static List<String> suggestedQuestions(Map<String, Object> meta) {
        return List.of(
                "Proč data končí tak brzy?",
                "Je to sazba centrální banky nebo bankovní úvěrová sazba?",
                "Kde najdu aktuálnější policy rate?");
    }

    public static Map<String, Object> normalizeMetaPublic(Map<String, Object> body) {
        return normalizeMeta(body);
    }

    private static Map<String, Object> normalizeMeta(Map<String, Object> body) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (body == null) {
            return meta;
        }
        for (String key :
                List.of(
                        "source_type",
                        "catalog_id",
                        "set_id",
                        "dataset_id",
                        "title",
                        "name",
                        "unit",
                        "frequency",
                        "country_label",
                        "geo_label",
                        "last_period",
                        "last_date",
                        "selected_indicator_name",
                        "indicator_name",
                        "query_params")) {
            if (body.containsKey(key)) {
                meta.put(key, body.get(key));
            }
        }
        return meta;
    }

    private static String metaBlob(Map<String, Object> meta) {
        StringBuilder sb = new StringBuilder();
        sb.append(stringOrBlank(meta.get("title"))).append(' ');
        sb.append(stringOrBlank(meta.get("name"))).append(' ');
        sb.append(stringOrBlank(meta.get("set_id"))).append(' ');
        sb.append(stringOrBlank(meta.get("selected_indicator_name"))).append(' ');
        sb.append(stringOrBlank(meta.get("indicator_name"))).append(' ');
        Object qp = meta.get("query_params");
        if (qp != null) {
            sb.append(qp);
        }
        return sb.toString().toLowerCase();
    }

    private static Integer extractYear(String period) {
        Matcher matcher = YEAR_RE.matcher(period != null ? period : "");
        if (matcher.find()) {
            return Integer.parseInt(matcher.group());
        }
        return null;
    }

    private static int freshnessMinYear() {
        String raw = BankIntelEnvVars.get("MANAGER_FRESHNESS_MIN_YEAR");
        if (raw != null && !raw.isBlank()) {
            try {
                return Integer.parseInt(raw.trim());
            } catch (NumberFormatException ignored) {
                // default below
            }
        }
        return Year.now().getValue() - 2;
    }

    private static String stringOrBlank(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }
}
