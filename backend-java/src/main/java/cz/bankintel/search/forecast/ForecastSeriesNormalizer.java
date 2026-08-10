package cz.bankintel.search.forecast;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns the connector-normalized preview rows (every connector already emits canonical
 * {@code date}/{@code value} keys — see {@code FredConnector}, {@code ConnectorParseSupport})
 * into the generic {@code SeriesInput} shape consumed by the Java forecast engine.
 * Source-agnostic on purpose: no per-connector branching.
 */
public final class ForecastSeriesNormalizer {

    private static final Pattern QUARTER = Pattern.compile("^(\\d{4})[-_ ]?Q([1-4])$", Pattern.CASE_INSENSITIVE);
    private static final Pattern YEAR_ONLY = Pattern.compile("^\\d{4}$");
    private static final Pattern YEAR_MONTH = Pattern.compile("^\\d{4}-\\d{2}$");
    private static final Pattern YEAR_MONTH_DAY = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}");

    private ForecastSeriesNormalizer() {}

    public record NormalizedSeries(
            String seriesId,
            String name,
            String source,
            String geo,
            String unit,
            String frequency,
            String seasonalAdjustment,
            List<Map<String, Object>> observations,
            int usableObservationCount) {}

    public static NormalizedSeries normalize(
            List<Map<String, Object>> rows,
            String seriesId,
            String name,
            String source,
            String geoHint,
            String role,
            String explicitFrequency) {
        List<Map<String, Object>> observations = new ArrayList<>();
        String geo = geoHint;
        String unit = null;
        String freqCode = explicitFrequency;
        String adjustment = null;

        for (Map<String, Object> row : rows) {
            String date = firstNonBlank(row.get("date"), row.get("TIME_PERIOD"), row.get("period"), row.get("time"));
            Double value = toDouble(firstNonNull(row.get("value"), row.get("amount"), row.get("OBS_VALUE")));
            if (date == null || date.isBlank() || value == null) {
                continue;
            }
            Map<String, Object> obs = new LinkedHashMap<>();
            obs.put("date", date.trim());
            obs.put("value", value);
            observations.add(obs);

            if (geo == null || geo.isBlank()) {
                geo = firstNonBlankStr(row.get("geo_label"), row.get("geo"), row.get("REF_AREA"), row.get("country"));
            }
            if (unit == null) {
                unit = firstNonBlankStr(row.get("unit_label"), row.get("unit"));
            }
            if (freqCode == null || freqCode.isBlank()) {
                freqCode = firstNonBlankStr(row.get("freq"), row.get("FREQ"), row.get("freq_label"));
            }
            if (adjustment == null) {
                adjustment = firstNonBlankStr(row.get("ADJUSTMENT"), row.get("adjustment"), row.get("s_adj"));
            }
        }

        observations.sort(Comparator.comparing(obs -> periodSortKey(String.valueOf(obs.get("date")))));

        String frequency = normalizeFrequencyCode(freqCode, observations);
        String seasonalAdjustment = normalizeAdjustment(adjustment);

        return new NormalizedSeries(
                seriesId, name, source, blankToNull(geo), blankToNull(unit), frequency, seasonalAdjustment, observations, observations.size());
    }

    public static Map<String, Object> toSeriesInputMap(NormalizedSeries s, String role, String stockOrFlow, String priceBasis, String transformation) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("series_id", s.seriesId());
        out.put("name", s.name());
        out.put("source", s.source());
        if (s.geo() != null) out.put("geo", s.geo());
        if (s.unit() != null) out.put("unit", s.unit());
        out.put("frequency", s.frequency());
        if (s.seasonalAdjustment() != null) out.put("seasonal_adjustment", s.seasonalAdjustment());
        if (priceBasis != null) out.put("price_basis", priceBasis);
        if (transformation != null) out.put("transformation", transformation);
        if (stockOrFlow != null) out.put("stock_or_flow", stockOrFlow);
        if (role != null && !role.isBlank()) out.put("role", role);
        out.put("observations", s.observations());
        return out;
    }

    private static String normalizeFrequencyCode(String raw, List<Map<String, Object>> observations) {
        if (raw != null) {
            String upper = raw.trim().toUpperCase(Locale.ROOT);
            switch (upper) {
                case "D", "DAILY" -> {
                    return "D";
                }
                case "W", "WEEKLY" -> {
                    return "W";
                }
                case "M", "MONTHLY" -> {
                    return "M";
                }
                case "Q", "QUARTERLY" -> {
                    return "Q";
                }
                case "A", "Y", "ANNUAL", "YEARLY" -> {
                    return "Y";
                }
                default -> {
                    // fall through to date-shape inference below
                }
            }
        }
        if (observations.isEmpty()) {
            return "M";
        }
        String sample = String.valueOf(observations.get(observations.size() - 1).get("date"));
        if (QUARTER.matcher(sample).matches()) {
            return "Q";
        }
        if (YEAR_MONTH_DAY.matcher(sample).find()) {
            // Full ISO dates don't by themselves reveal periodicity — many sources (e.g. FRED)
            // encode monthly/quarterly/annual observations as the first day of the period. Infer
            // the true step from the actual gaps between observations instead of assuming daily.
            String inferred = inferFrequencyFromDateGaps(observations);
            return inferred != null ? inferred : "D";
        }
        if (YEAR_MONTH.matcher(sample).matches()) {
            return "M";
        }
        if (YEAR_ONLY.matcher(sample).matches()) {
            return "Y";
        }
        return "M";
    }

    private static String inferFrequencyFromDateGaps(List<Map<String, Object>> observations) {
        List<LocalDate> dates = new ArrayList<>();
        int start = Math.max(0, observations.size() - 13);
        for (int i = start; i < observations.size(); i++) {
            String raw = String.valueOf(observations.get(i).get("date"));
            try {
                dates.add(LocalDate.parse(raw.length() > 10 ? raw.substring(0, 10) : raw));
            } catch (DateTimeParseException ignored) {
                // skip unparsable entries; median below still works with fewer samples
            }
        }
        if (dates.size() < 2) {
            return null;
        }
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < dates.size(); i++) {
            gaps.add(java.time.temporal.ChronoUnit.DAYS.between(dates.get(i - 1), dates.get(i)));
        }
        gaps.sort(Long::compareTo);
        long median = gaps.get(gaps.size() / 2);
        if (median <= 3) {
            return "D";
        }
        if (median <= 10) {
            return "W";
        }
        if (median <= 45) {
            return "M";
        }
        if (median <= 135) {
            return "Q";
        }
        return "Y";
    }

    private static String normalizeAdjustment(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        if (upper.contains("SA") || upper.contains("SEASONALLY ADJUSTED") || upper.contains("SEZONN")) {
            return "sa";
        }
        if (upper.contains("NSA") || upper.contains("NOT ADJUSTED") || upper.contains("NEOCIST")) {
            return "nsa";
        }
        return "unknown";
    }

    private static String periodSortKey(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isBlank()) {
            return "";
        }
        String upper = value.toUpperCase(Locale.ROOT);
        java.util.regex.Matcher quarter = QUARTER.matcher(upper);
        if (quarter.matches()) {
            return quarter.group(1) + "-Q" + quarter.group(2);
        }
        if (YEAR_ONLY.matcher(upper).matches()) {
            return upper + "-00-00";
        }
        if (YEAR_MONTH.matcher(upper).matches()) {
            return upper + "-00";
        }
        if (YEAR_MONTH_DAY.matcher(upper).find()) {
            return upper.substring(0, Math.min(10, upper.length()));
        }
        return upper;
    }

    private static String firstNonBlank(Object... values) {
        for (Object v : values) {
            if (v != null && !String.valueOf(v).isBlank()) {
                return String.valueOf(v);
            }
        }
        return null;
    }

    private static String firstNonBlankStr(Object... values) {
        return firstNonBlank(values);
    }

    private static Object firstNonNull(Object... values) {
        for (Object v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
        }
        try {
            String s = String.valueOf(value).trim().replace(",", ".");
            if (s.isBlank()) {
                return null;
            }
            double d = Double.parseDouble(s);
            return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
