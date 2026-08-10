package cz.bankintel.search;

import cz.bankintel.search.model.CatalogKeys;
import cz.bankintel.search.model.CatalogMapSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Near-duplicate consolidation within a source — port of Python {@code _near_dup_display_signature}
 * + {@code _consolidate_near_duplicate_display_rows} (catalog_deep_search.py).
 */
public final class CatalogSearchVariantDedup {

    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    private static final Pattern PERIOD_TIMESTAMP =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}|\\d{4}-\\d{2}-\\d{2}t\\d", Pattern.CASE_INSENSITIVE);
    private static final Pattern FREQ_COLLAPSE = Pattern.compile("\\b(a|q|m|d|annual|quarterly|monthly|yearly)\\b");

    private CatalogSearchVariantDedup() {}

    public static List<Map<String, Object>> consolidateDisplayRows(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Map<String, Object>> bestBySig = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String sig = displaySignature(row);
            if (sig.isBlank()) {
                out.add(row);
                continue;
            }
            Map<String, Object> kept = bestBySig.get(sig);
            if (kept == null) {
                bestBySig.put(sig, new LinkedHashMap<>(row));
                continue;
            }
        int keptScore = CatalogMapSupport.toInt(kept.get(CatalogKeys.SEARCH_SCORE), CatalogMapSupport.toInt(kept.get(CatalogKeys.MATCH), 0));
            int rowScore = CatalogMapSupport.toInt(row.get(CatalogKeys.SEARCH_SCORE), CatalogMapSupport.toInt(row.get(CatalogKeys.MATCH), 0));
            if (rowScore > keptScore) {
                bestBySig.put(sig, new LinkedHashMap<>(row));
                kept = bestBySig.get(sig);
            }
            kept.put("_near_duplicates_collapsed", (int) kept.getOrDefault("_near_duplicates_collapsed", 0) + 1);
        }
        out.addAll(bestBySig.values());
        out.sort((a, b) -> Integer.compare(scoreOf(b), scoreOf(a)));
        return out;
    }

    public static String displaySignature(Map<String, Object> row) {
        if (row == null) {
            return "";
        }
        String cid = CatalogMapSupport.str(firstNonBlank(row.get(CatalogKeys.CATALOG_ID), row.get(CatalogKeys.SOURCE_TYPE), row.get(CatalogKeys.SOURCE)))
                .toLowerCase(Locale.ROOT);
        String normTitle = normalizeToken(CatalogMapSupport.str(firstNonBlank(row.get(CatalogKeys.NAME), row.get(CatalogKeys.TITLE))));
        if (normTitle.isBlank()) {
            return "";
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = row.get("row") instanceof Map<?, ?> map ? castMap(map) : row;
        String territory = normalizeToken(str(firstNonBlank(
                row.get("territory"),
                nested.get("territory"),
                nested.get("country_or_region"),
                row.get("country_hint"))));
        String periodRaw = str(firstNonBlank(row.get("period"), nested.get("period"), nested.get("frequency")));
        String periodSig = PERIOD_TIMESTAMP.matcher(periodRaw).find() ? "" : normalizeToken(periodRaw);
        return cid + "|" + normTitle + "|" + territory + "|" + periodSig;
    }

    /** Normalize ECB set_id for dedup — ref Python {@code parse_ecb_set_id} usage in search cache. */
    public static String parseEcbSetIdKey(String setId) {
        String raw = CatalogMapSupport.str(setId);
        if (raw.isBlank()) {
            return "";
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.startsWith("ecb:")) {
            String[] parts = raw.split(":", 3);
            if (parts.length == 3) {
                return ("ecb:" + parts[1].toUpperCase(Locale.ROOT) + ":" + normalizeToken(parts[2]))
                        .toLowerCase(Locale.ROOT);
            }
        }
        return normalizeToken(raw);
    }

    private static String normalizeToken(String text) {
        String folded = CatalogTextUtils.foldAscii(text);
        String collapsed = FREQ_COLLAPSE.matcher(folded).replaceAll(" ");
        return NON_ALNUM.matcher(collapsed).replaceAll(" ").trim().replaceAll("\\s+", " ");
    }

    private static int scoreOf(Map<String, Object> row) {
        return CatalogMapSupport.toInt(row.get(CatalogKeys.SEARCH_SCORE), CatalogMapSupport.toInt(row.get(CatalogKeys.MATCH), 0));
    }

    private static String str(Object value) {
        return CatalogMapSupport.str(value);
    }

    private static String firstNonBlank(Object... values) {
        return CatalogMapSupport.firstNonBlank(values);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }
}
