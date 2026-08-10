package cz.bankintel.sources.oecd;

import java.util.Locale;

public record OecdSdmx2SetId(String agency, String dataflow, String version, String filterExpression) {

    public static OecdSdmx2SetId parse(String setId) {
        String raw = setId != null ? setId.trim() : "";
        if (raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split("\\|");
        if (parts.length < 5) {
            return null;
        }
        if (!"SDMX2".equalsIgnoreCase(parts[0])) {
            return null;
        }
        return new OecdSdmx2SetId(parts[1], parts[2], parts[3], parts[4]);
    }

    String refArea() {
        if (filterExpression.isBlank()) {
            return "";
        }
        return filterExpression.split("\\.")[0].trim().toUpperCase(Locale.ROOT);
    }
}
