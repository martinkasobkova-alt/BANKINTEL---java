package cz.bankintel.explore.manager.refresh;

import cz.bankintel.explore.manager.refresh.ManagerEurostatRefreshTargetBuilder.RefreshTarget;
import cz.bankintel.util.BankIntelEnvVars;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bson.Document;

/**
 * Pure port of the legacy Python {@code services/manager_series_cache.py}: builds the exact
 * Mongo {@code manager_series_cache} document shape {@link
 * cz.bankintel.explore.manager.ManagerSeriesCacheReader} already reads, plus the two staleness
 * gates that decide whether a fetched series is worth writing as "loaded" data at all. No
 * Mongo, no HTTP - everything here is a deterministic function of its inputs, so it's fully
 * unit-testable without touching a live database or network.
 */
public final class ManagerSeriesCacheDocBuilder {

    private static final Pattern MONTH_PERIOD = Pattern.compile("^(\\d{4})-(\\d{2})");
    private static final Pattern QUARTER_PERIOD = Pattern.compile("^(\\d{4})-?Q([1-4])", Pattern.CASE_INSENSITIVE);
    private static final Pattern YEAR_PERIOD = Pattern.compile("^(\\d{4})$");

    /** {@code freq -> (freshLimitMonths, usableLimitMonths)}, identical to Python's thresholds. */
    private static final Map<String, int[]> FRESHNESS_THRESHOLDS = Map.of(
            "M", new int[] {9, 15},
            "Q", new int[] {15, 21},
            "A", new int[] {30, 42},
            "D", new int[] {2, 4});
    private static final int[] DEFAULT_THRESHOLD = {18, 30};

    private ManagerSeriesCacheDocBuilder() {}

    public record FreshnessResult(String freshness, String freshnessCategory, Integer dataLagMonths, Integer expectedLagMonths) {}

    /**
     * Hard pre-write gate: a fetched series whose latest observation's year is more than 2
     * years behind {@code now} (env-overridable via {@code MANAGER_STALE_MIN_YEAR}, same as
     * Python) must never be written as loaded data - the caller should treat it as a fetch
     * failure and call {@link #buildUnavailableDoc} with a stale-year reason instead. Absence
     * of a parseable year is NOT treated as stale (mirrors Python: a gate that deletes data
     * just because metadata is missing would be worse than the problem it prevents).
     */
    public static boolean isStaleByHardYearGate(String latestPeriod, Instant now) {
        Integer year = extractYear(latestPeriod);
        if (year == null) {
            return false;
        }
        return year < minStaleYear(now);
    }

    private static int minStaleYear(Instant now) {
        String override = BankIntelEnvVars.get("MANAGER_STALE_MIN_YEAR");
        if (override != null && override.matches("\\d+")) {
            return Integer.parseInt(override);
        }
        return ZonedDateTime.ofInstant(now, ZoneOffset.UTC).getYear() - 2;
    }

    private static Integer extractYear(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher m = Pattern.compile("(\\d{4})").matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    /**
     * Builds the "loaded" document for a target with real observations. Returns {@code null}
     * if {@code observations} is empty (caller should build an unavailable doc instead) - this
     * never happens for a target that has already passed the hard staleness gate with a real
     * latest period, but stays defensive since this class has no control over caller ordering.
     */
    public static Document buildLoadedDoc(RefreshTarget target, List<Map<String, Object>> observations, Instant now) {
        if (observations == null || observations.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> sorted = new ArrayList<>(observations);
        sorted.sort((a, b) -> str(a.get("period")).compareTo(str(b.get("period"))));
        Map<String, Object> latest = sorted.get(sorted.size() - 1);
        String latestPeriod = str(latest.get("period"));
        Double latestValue = toDouble(latest.get("value"));
        Map<String, Object> row = target.row();
        String frequency = frequencyCode(row.get("frequency"), latestPeriod);
        String source = "eurostat";
        String seriesId = target.seriesId();
        String geoU = target.geo().toUpperCase(Locale.ROOT);
        FreshnessResult freshnessInfo = classifyFreshness(latestPeriod, frequency, isHistoricalSeries(row), now);
        String freshness = freshnessInfo.freshness();
        String freshnessCategory = freshnessInfo.freshnessCategory();
        String staleReason = "stale_suspicious".equals(freshness) ? freshnessCategory : null;
        Map<String, Double> changes = changesByFrequency(sorted, frequency);
        String title = str(firstNonBlank(row.get("title"), row.get("indicator_name"), seriesId));

        Document doc = new Document();
        doc.put("_id", source + ":" + seriesId + ":" + geoU);
        doc.put("series_id", seriesId);
        doc.put("source", source);
        doc.put("segment_id", target.segmentId());
        doc.put("indicator_title", title);
        doc.put("title", title);
        doc.put("geo_label", geoU);
        doc.put("geo", geoU);
        doc.put("unit", blankToNull(str(firstNonBlank(row.get("unit"), target.queryParams().get("unit")))));
        doc.put("frequency", frequency);
        doc.put("latest_period", latestPeriod);
        doc.put("latest_value", latestValue);
        doc.put("yoy_change_pct", changes.get("yoy"));
        doc.put("mom_change_pct", changes.get("mom"));
        doc.put("qoq_change_pct", changes.get("qoq"));
        doc.put("observations_5y", trim5y(sorted, frequency));
        doc.put("updated_at", now);
        doc.put("data_lag_months", freshnessInfo.dataLagMonths());
        doc.put("expected_lag_months", freshnessInfo.expectedLagMonths());
        doc.put("freshness", freshness);
        doc.put("freshness_category", freshnessCategory);
        doc.put("stale_reason", staleReason);
        doc.put("fetch_source_path", "live_fetch");
        doc.put("query_params", target.queryParams());
        doc.put("dataset_id", target.datasetId());
        doc.put("manager_category", row.get("manager_category"));
        doc.put("segment_roles", row.getOrDefault("segment_roles", List.of()));
        doc.put("category_bucket", firstNonBlank(row.get("manager_category"), row.get("indicator_role"), row.get("signal_type")));
        doc.put("indicator_role", row.get("indicator_role"));
        doc.put("manager_series_tier", row.get("manager_series_tier"));
        return doc;
    }

    /** Same {@code _id}/key shape as {@link #buildLoadedDoc}, for a target that failed to
     * fetch, was empty, or was rejected by {@link #isStaleByHardYearGate}. */
    public static Document buildUnavailableDoc(RefreshTarget target, String reason, Instant now) {
        Map<String, Object> row = target.row();
        String source = "eurostat";
        String seriesId = target.seriesId();
        String geoU = target.geo().toUpperCase(Locale.ROOT);
        String title = str(firstNonBlank(row.get("title"), row.get("indicator_name"), seriesId));

        Document doc = new Document();
        doc.put("_id", source + ":" + seriesId + ":" + geoU);
        doc.put("series_id", seriesId);
        doc.put("source", source);
        doc.put("segment_id", target.segmentId());
        doc.put("indicator_title", title);
        doc.put("title", title);
        doc.put("geo_label", geoU);
        doc.put("geo", geoU);
        doc.put("unit", blankToNull(str(row.get("unit"))));
        doc.put("frequency", blankToNull(str(row.get("frequency"))));
        doc.put("latest_period", null);
        doc.put("latest_value", null);
        doc.put("yoy_change_pct", null);
        doc.put("mom_change_pct", null);
        doc.put("qoq_change_pct", null);
        doc.put("observations_5y", List.of());
        doc.put("updated_at", now);
        doc.put("data_lag_months", null);
        doc.put("expected_lag_months", null);
        doc.put("freshness", "unavailable");
        doc.put("freshness_category", "truly_unavailable");
        doc.put("unavailable_reason", reason == null ? "fetch_failed" : reason.substring(0, Math.min(240, reason.length())));
        doc.put("fetch_source_path", "live_fetch");
        doc.put("query_params", target.queryParams());
        doc.put("dataset_id", target.datasetId());
        doc.put("segment_roles", row.getOrDefault("segment_roles", List.of()));
        doc.put("category_bucket", firstNonBlank(row.get("manager_category"), row.get("indicator_role"), row.get("signal_type")));
        return doc;
    }

    static FreshnessResult classifyFreshness(String latestPeriod, String frequency, boolean historical, Instant now) {
        if (latestPeriod == null || latestPeriod.isBlank()) {
            return new FreshnessResult("unavailable", "truly_unavailable", null, null);
        }
        Integer lag = dataLagMonths(latestPeriod, now);
        if (historical) {
            return new FreshnessResult("historical", "historical_dataset", lag, null);
        }
        int[] thresholds = FRESHNESS_THRESHOLDS.getOrDefault(frequency, DEFAULT_THRESHOLD);
        int freshLimit = thresholds[0];
        int usableLimit = thresholds[1];
        if (lag == null) {
            return new FreshnessResult("usable_lagged", "expected_lag", null, usableLimit);
        }
        if (lag <= freshLimit) {
            return new FreshnessResult("fresh", "expected_lag", lag, usableLimit);
        }
        if (lag <= usableLimit) {
            return new FreshnessResult("usable_lagged", "expected_lag", lag, usableLimit);
        }
        return new FreshnessResult("stale_suspicious", "suspicious_stale", lag, usableLimit);
    }

    static boolean isHistoricalSeries(Map<String, Object> row) {
        if (isTruthy(row.get("historical")) || isTruthy(row.get("is_historical")) || isTruthy(row.get("historical_dataset"))) {
            return true;
        }
        String haystack = String.join(
                        " ",
                        str(row.get("dataset_id")),
                        str(row.get("series_id")),
                        str(row.get("title")),
                        str(row.get("indicator_title")),
                        str(row.get("manager_category")))
                .toLowerCase(Locale.ROOT);
        return haystack.contains("historical") || haystack.contains("history") || haystack.contains("archive")
                || haystack.contains("archiv");
    }

    static Integer dataLagMonths(String latestPeriod, Instant now) {
        Integer idx = periodToMonthIndex(latestPeriod);
        if (idx == null) {
            return null;
        }
        ZonedDateTime nowUtc = ZonedDateTime.ofInstant(now, ZoneOffset.UTC);
        int current = nowUtc.getYear() * 12 + nowUtc.getMonthValue();
        return Math.max(0, current - idx);
    }

    static Integer periodToMonthIndex(String period) {
        if (period == null) {
            return null;
        }
        String text = period.trim();
        Matcher month = MONTH_PERIOD.matcher(text);
        if (month.find()) {
            return Integer.parseInt(month.group(1)) * 12 + Integer.parseInt(month.group(2));
        }
        Matcher quarter = QUARTER_PERIOD.matcher(text);
        if (quarter.find()) {
            return Integer.parseInt(quarter.group(1)) * 12 + (Integer.parseInt(quarter.group(2)) - 1) * 3 + 1;
        }
        Matcher year = YEAR_PERIOD.matcher(text);
        if (year.find()) {
            return Integer.parseInt(year.group(1)) * 12 + 12;
        }
        return null;
    }

    static String frequencyCode(Object rawValue, String latestPeriod) {
        String raw = str(rawValue).toUpperCase(Locale.ROOT);
        if (Set.of("M", "MONTH", "MONTHLY").contains(raw)) {
            return "M";
        }
        if (Set.of("Q", "QUARTER", "QUARTERLY").contains(raw)) {
            return "Q";
        }
        if (Set.of("A", "Y", "YEAR", "ANNUAL", "YEARLY").contains(raw)) {
            return "A";
        }
        if (Set.of("D", "DAY", "DAILY").contains(raw)) {
            return "D";
        }
        String inferred = inferFrequencyFromPeriod(latestPeriod);
        if (inferred != null) {
            return inferred;
        }
        return raw.isBlank() ? "unknown" : raw;
    }

    private static String inferFrequencyFromPeriod(String period) {
        if (period == null) {
            return null;
        }
        String text = period.trim();
        if (MONTH_PERIOD.matcher(text).find()) {
            return "M";
        }
        if (QUARTER_PERIOD.matcher(text).find()) {
            return "Q";
        }
        if (YEAR_PERIOD.matcher(text).find()) {
            return "A";
        }
        return null;
    }

    private static List<Map<String, Object>> trim5y(List<Map<String, Object>> observations, String frequency) {
        int keep = switch (frequency) {
            case "M" -> 60;
            case "Q" -> 20;
            case "A" -> 6;
            default -> 60;
        };
        int from = Math.max(0, observations.size() - keep);
        return new ArrayList<>(observations.subList(from, observations.size()));
    }

    private static Map<String, Double> changesByFrequency(List<Map<String, Object>> observations, String frequency) {
        Map<String, Double> out = new LinkedHashMap<>();
        out.put("mom", "M".equals(frequency) ? changeVsOffset(observations, 1) : null);
        out.put("qoq", "Q".equals(frequency) ? changeVsOffset(observations, 1) : null);
        Double yoy = switch (frequency) {
            case "M" -> changeVsOffset(observations, 12);
            case "Q" -> changeVsOffset(observations, 4);
            case "A" -> changeVsOffset(observations, 1);
            default -> null;
        };
        out.put("yoy", yoy);
        return out;
    }

    private static Double changeVsOffset(List<Map<String, Object>> observations, int periodsBack) {
        int n = observations.size();
        if (n <= periodsBack) {
            return null;
        }
        Double current = toDouble(observations.get(n - 1).get("value"));
        Double previous = toDouble(observations.get(n - 1 - periodsBack).get("value"));
        if (current == null || previous == null || previous == 0.0) {
            return null;
        }
        return Math.round(((current - previous) / Math.abs(previous)) * 100.0 * 1000.0) / 1000.0;
    }

    private static boolean isTruthy(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(str(value));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Object firstNonBlank(Object... values) {
        for (Object value : values) {
            String text = str(value);
            if (!text.isBlank()) {
                return value instanceof String ? text : value;
            }
        }
        return "";
    }

    private static Double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s.trim().replace(",", "."));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }
}
