package cz.bankintel.search.analytics;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Human-readable Czech formatting for analytics narrative — compact units (mil./mld.),
 * readable periods, no raw series IDs in user-facing text.
 */
public final class AnalyticsValueFormatter {

    private static final Locale CS = Locale.forLanguageTag("cs-CZ");
    private static final Pattern ISO_DATE = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})$");
    private static final Pattern YEAR_MONTH = Pattern.compile("^(\\d{4})-(\\d{2})$");
    private static final Pattern QUARTER = Pattern.compile("^(\\d{4})-Q([1-4])$", Pattern.CASE_INSENSITIVE);

    private AnalyticsValueFormatter() {}

    static String formatPeriod(String raw) {
        if (raw == null || raw.isBlank()) {
            return "poslední dostupné období";
        }
        String s = raw.trim();
        Matcher iso = ISO_DATE.matcher(s);
        if (iso.matches()) {
            int d = Integer.parseInt(iso.group(3));
            int m = Integer.parseInt(iso.group(2));
            int y = Integer.parseInt(iso.group(1));
            return String.format(CS, "%d. %d. %d", d, m, y);
        }
        Matcher q = QUARTER.matcher(s);
        if (q.matches()) {
            return q.group(1) + " Q" + q.group(2);
        }
        Matcher ym = YEAR_MONTH.matcher(s);
        if (ym.matches()) {
            int m = Integer.parseInt(ym.group(2));
            int y = Integer.parseInt(ym.group(1));
            return String.format(CS, "%d/%d", m, y);
        }
        if (s.matches("\\d{8}")) {
            int y = Integer.parseInt(s.substring(0, 4));
            int m = Integer.parseInt(s.substring(4, 6));
            int d = Integer.parseInt(s.substring(6, 8));
            return String.format(CS, "%d. %d. %d", d, m, y);
        }
        return s;
    }

    static boolean isPercentUnit(String unit) {
        if (unit == null || unit.isBlank()) {
            return false;
        }
        String u = unit.trim().toLowerCase(CS);
        return u.contains("%") || u.contains("procent") || u.equals("pp") || u.contains("p.b.");
    }

    public static String formatValue(double value, String unit) {
        if (isPercentUnit(unit) || looksLikeRate(value, unit)) {
            return formatDecimal(value, 1) + " %";
        }
        String compact = formatCompact(value);
        if (unit != null && !unit.isBlank() && !isGenericUnit(unit)) {
            return compact + " " + normalizeUnitLabel(unit);
        }
        return compact;
    }

    public static String formatCompact(double value) {
        double abs = Math.abs(value);
        if (abs >= 1e12) {
            return formatDecimal(value / 1e12, 2) + " bil.";
        }
        if (abs >= 1e9) {
            return formatDecimal(value / 1e9, 2) + " mld.";
        }
        if (abs >= 1e6) {
            return formatDecimal(value / 1e6, 2) + " mil.";
        }
        if (abs >= 1e4) {
            return formatDecimal(value / 1e3, 1) + " tis.";
        }
        if (abs >= 1000) {
            return formatDecimal(value, 0);
        }
        return formatDecimal(value, Math.abs(value) >= 10 ? 1 : 2);
    }

    static String formatDecimal(double value, int digits) {
        return String.format(CS, "%,." + digits + "f", value);
    }

    static String humanSeriesLabel(String preferredName, String fallbackId) {
        String name = preferredName == null ? "" : preferredName.trim();
        if (!name.isBlank() && !looksLikeTechnicalId(name)) {
            return name;
        }
        if (fallbackId != null && !fallbackId.isBlank() && !looksLikeTechnicalId(fallbackId)) {
            return fallbackId.trim();
        }
        return name.isBlank() ? "vybraná datová řada" : name;
    }

    static String humanRelationshipLabel(Map<String, Object> rel) {
        String concept = str(rel.get("concept"));
        if (!concept.isBlank()) {
            return concept;
        }
        String seriesName = str(rel.get("series_name"));
        if (!seriesName.isBlank() && !looksLikeTechnicalId(seriesName)) {
            return seriesName;
        }
        String labelB = str(rel.get("label_b"));
        if (!labelB.isBlank() && !looksLikeTechnicalId(labelB)) {
            return labelB;
        }
        return "související ukazatel";
    }

    static boolean looksLikeTechnicalId(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String s = text.trim();
        if (s.contains(":") && s.matches("(?i)[a-z]+:[\\w.-]+")) {
            return true;
        }
        if (s.matches("(?i)^[a-z]{2,10}:\\d+[\\w.-]*$")) {
            return true;
        }
        return s.length() > 48 && !s.contains(" ");
    }

    private static boolean looksLikeRate(double value, String unit) {
        if (unit != null && !unit.isBlank()) {
            return false;
        }
        return Math.abs(value) <= 100 && Math.abs(value - Math.rint(value * 10) / 10.0) < 0.0001;
    }

    private static boolean isGenericUnit(String unit) {
        String u = unit.trim().toLowerCase(CS);
        return u.equals("value") || u.equals("hodnota") || u.equals("index") || u.equals("level");
    }

    private static String normalizeUnitLabel(String unit) {
        String u = unit.trim();
        if (u.equalsIgnoreCase("CZK") || u.equalsIgnoreCase("Kč")) {
            return "Kč";
        }
        return u;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
