package cz.bankintel.sources.ecb;

import java.util.regex.Pattern;

public final class EcbReference {

    private static final Pattern SERIES_DOT_PATTERN =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9,+._-]*(?:\\.(?:[A-Za-z0-9,+._-]*)+)+$");

    private EcbReference() {}

    public record Parsed(
            String flowRef,
            String seriesKey,
            String raw,
            boolean wildcard,
            boolean hasDimensionOr,
            boolean validPreviewTarget) {

        public String setIdCompat() {
            return flowRef + "/" + seriesKey;
        }
    }

    public static Parsed parseSetId(String raw) {
        String s = raw != null ? raw.trim() : "";
        if (s.isBlank() || s.toUpperCase().contains("||DATAFLOW") || !s.contains("/")) {
            return null;
        }
        int slash = s.indexOf('/');
        String flowRef = s.substring(0, slash).trim();
        String seriesKey = s.substring(slash + 1).trim();
        if (flowRef.isBlank() || seriesKey.isBlank()) {
            return null;
        }
        boolean wildcard = seriesKey.contains("..");
        boolean hasDimensionOr = seriesKey.contains("+");
        boolean valid = isValidPreviewSeries(flowRef, seriesKey);
        return new Parsed(flowRef, seriesKey, s, wildcard, hasDimensionOr, valid);
    }

    static boolean isValidPreviewSeries(String flowRef, String seriesKey) {
        String flow = stringOrBlank(flowRef);
        String sk = stringOrBlank(seriesKey);
        if (flow.isBlank() || sk.isBlank()) {
            return false;
        }
        if (sk.equalsIgnoreCase(flow) && !sk.contains(".") && !sk.contains("+")) {
            return false;
        }
        if ("DATAFLOW".equalsIgnoreCase(sk) || "ECB".equalsIgnoreCase(sk) || "ALL".equalsIgnoreCase(sk) || "SERIES".equalsIgnoreCase(sk)) {
            return false;
        }
        if ("MPD".equalsIgnoreCase(flow) && !looksLikeDimensionsOnly(sk) && !sk.contains(".")) {
            return false;
        }
        return looksLikeDimensionsOnly(sk);
    }

    private static boolean looksLikeDimensionsOnly(String seriesKey) {
        String sk = stringOrBlank(seriesKey);
        if (sk.length() < 5) {
            return false;
        }
        if ("DATAFLOW".equalsIgnoreCase(sk) || "ALL".equalsIgnoreCase(sk)) {
            return false;
        }
        if (sk.contains("+") || sk.contains("..")) {
            return true;
        }
        return sk.contains(".") && SERIES_DOT_PATTERN.matcher(sk).matches();
    }

    private static String stringOrBlank(String value) {
        return value != null ? value.trim() : "";
    }
}
