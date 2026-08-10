package cz.bankintel.search.v2.observability;

import java.util.ArrayList;
import java.util.List;

/**
 * Conservative, dependency-free text/PII hygiene for telemetry payloads.
 *
 * <p>This is intentionally a technical minimum, not a PII classifier: bound every string length,
 * strip control characters, and cap list sizes so a single event can never grow unbounded or break
 * JSON serialization. Callers are responsible for never passing raw LLM responses, secrets, auth
 * headers, or tokenized URLs into any field routed through this class (see class-level notes in
 * {@link SearchV2TelemetryEventBuilder}).
 */
public final class SearchV2TelemetrySanitizer {

    public static final int DEFAULT_QUERY_MAX_LENGTH = 500;
    public static final int DEFAULT_TEXT_MAX_LENGTH = 300;
    public static final int DEFAULT_SHORT_TEXT_MAX_LENGTH = 200;
    public static final int DEFAULT_LIST_MAX_SIZE = 10;

    private SearchV2TelemetrySanitizer() {}

    /** Strips ASCII/Unicode control characters (except plain space) and collapses to a single line. */
    public static String stripControlCharacters(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                out.append(' ');
                continue;
            }
            if (Character.isISOControl(c)) {
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    public static String sanitizeText(String value, int maxLength) {
        String stripped = stripControlCharacters(value).trim().replaceAll("\\s+", " ");
        if (stripped.isEmpty()) {
            return "";
        }
        int limit = Math.max(0, maxLength);
        return stripped.length() <= limit ? stripped : stripped.substring(0, limit).trim();
    }

    /** Same as {@link #sanitizeText(String, int)} but returns null instead of an empty string. */
    public static String sanitizeTextOrNull(String value, int maxLength) {
        String out = sanitizeText(value, maxLength);
        return out.isEmpty() ? null : out;
    }

    public static List<String> sanitizeList(List<String> values, int maxItems, int maxItemLength) {
        List<String> out = new ArrayList<>();
        if (values == null) {
            return out;
        }
        for (String value : values) {
            if (out.size() >= Math.max(0, maxItems)) {
                break;
            }
            String sanitized = sanitizeText(value, maxItemLength);
            if (!sanitized.isEmpty()) {
                out.add(sanitized);
            }
        }
        return out;
    }
}
