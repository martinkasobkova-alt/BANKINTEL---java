package cz.bankintel.search;

import java.util.Locale;
import java.util.Map;

/** Port of {@code arad_series_identity.py} — composite set_id:indicator_id. */
public final class AradSeriesIdentity {

    private AradSeriesIdentity() {}

    public static String compose(String setId, String indicatorId) {
        String sid = str(setId);
        String iid = str(indicatorId);
        if (sid.isBlank() || iid.isBlank()) {
            return sid.isBlank() ? iid : sid;
        }
        return sid + ":" + iid;
    }

    public static String[] parse(String seriesId) {
        String raw = str(seriesId);
        if (!raw.contains(":")) {
            return null;
        }
        String[] parts = raw.split(":", 2);
        String setId = parts[0].trim();
        String indicatorId = parts[1].trim();
        if (setId.isBlank() || indicatorId.isBlank()) {
            return null;
        }
        return new String[] {setId, indicatorId};
    }

    public static String fromRow(Map<String, Object> row) {
        if (row == null) {
            return "";
        }
        String setId = str(row.get("set_id"));
        String indicatorId = str(row.get("indicator_id"));
        if (indicatorId.isBlank()) {
            indicatorId = str(row.get("code"));
        }
        if (setId.isBlank() || indicatorId.isBlank()) {
            return setId;
        }
        return compose(setId, indicatorId);
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }
}
