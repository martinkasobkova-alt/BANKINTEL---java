package cz.bankintel.service.sync;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Dedupe klíč jednoho řádku při sync upsertu — stejná logika jako Python {@code _record_key}.
 */
public final class RecordKeyUtil {

    private static final List<String> PREFERRED_KEYS = List.of(
            "date", "period", "institution", "category",
            "symbol", "snapshot_id", "indicator_id",
            "set_id", "id", "currency");

    private RecordKeyUtil() {}

    public static String recordKey(Map<String, Object> record, String sourceType) {
        if ("csu".equalsIgnoreCase(sourceType)) {
            return signatureKey(record);
        }
        List<String> present = new ArrayList<>();
        for (String key : PREFERRED_KEYS) {
            Object value = record.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                present.add(String.valueOf(value));
            }
        }
        if (!present.isEmpty()) {
            return String.join("|", present);
        }
        return signatureKey(record);
    }

    private static String signatureKey(Map<String, Object> record) {
        List<Map.Entry<String, String>> items = new ArrayList<>();
        for (Map.Entry<String, Object> entry : record.entrySet()) {
            items.add(Map.entry(entry.getKey(), String.valueOf(entry.getValue())));
        }
        items.sort(Comparator.comparing(Map.Entry::getKey));
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> item : items) {
            parts.add(item.getKey() + "=" + item.getValue());
        }
        return String.join("|", parts);
    }
}
