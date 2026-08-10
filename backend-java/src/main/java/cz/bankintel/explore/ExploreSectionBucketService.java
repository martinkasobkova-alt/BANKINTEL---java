package cz.bankintel.explore;

import cz.bankintel.search.CatalogSourceRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class ExploreSectionBucketService {

    private static final Set<String> MACRO_SOURCES = CatalogSourceRegistry.EXPLORE_MACRO_SOURCES;

    public Map<String, List<Map<String, Object>>> bucketItemsBySection(
            List<Map<String, Object>> items, String primaryCountryCode) {
        Map<String, List<Map<String, Object>>> raw = new LinkedHashMap<>();
        raw.put("company", new ArrayList<>());
        raw.put("sector", new ArrayList<>());
        raw.put("commodity", new ArrayList<>());
        raw.put("financial_markets", new ArrayList<>());
        raw.put("macro", new ArrayList<>());
        raw.put("demographics", new ArrayList<>());
        raw.put("fx", new ArrayList<>());
        raw.put("neighbor", new ArrayList<>());
        raw.put("partner", new ArrayList<>());
        raw.put("eu", new ArrayList<>());
        raw.put("continent", new ArrayList<>());
        raw.put("global", new ArrayList<>());

        List<Map<String, Object>> relatedSectors = new ArrayList<>();
        for (Map<String, Object> item : items) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            if (isRelatedSectorItem(item)) {
                relatedSectors.add(item);
                continue;
            }
            String bucket = fetchItemBucket(item);
            if (Set.of("macro", "fx").contains(bucket) && geoConflictsWithPrimary(item, primaryCountryCode)) {
                bucket = "neighbor";
            }
            raw.computeIfAbsent(bucket, key -> new ArrayList<>()).add(item);
        }

        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        List<Map<String, Object>> sector = new ArrayList<>();
        sector.addAll(raw.get("company"));
        sector.addAll(raw.get("sector"));
        out.put("sector", sector);
        out.put("related_sectors", relatedSectors);
        out.put("commodities", raw.get("commodity"));
        out.put("financial_markets", raw.get("financial_markets"));
        out.put("macro", raw.get("macro"));
        out.put("demographics", raw.get("demographics"));
        out.put("fx", raw.get("fx"));
        out.put("neighbors", raw.get("neighbor"));
        out.put("partners", raw.get("partner"));
        out.put("eu", merge(raw.get("eu"), raw.get("continent")));
        out.put("global", raw.get("global"));
        out.put("regional_economy", merge(raw.get("neighbor"), raw.get("partner"), raw.get("eu"), raw.get("continent")));
        return out;
    }

    public List<Map<String, Object>> reportSectionItems(
            Map<String, List<Map<String, Object>>> bySection, String sectionId, String primaryCountryCode) {
        String sid = str(sectionId).toLowerCase(Locale.ROOT);
        if ("regional_economy".equals(sid)) {
            List<Map<String, Object>> merged = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (String bucketId : List.of("neighbors", "partners", "eu")) {
                for (Map<String, Object> item : bySection.getOrDefault(bucketId, List.of())) {
                    String key = str(item.get("set_id")) + "|" + str(item.get("title"));
                    if (seen.add(key)) {
                        merged.add(item);
                    }
                }
            }
            return merged;
        }
        if ("macro".equals(sid)) {
            String primary = str(primaryCountryCode).toUpperCase(Locale.ROOT);
            List<Map<String, Object>> macroItems = bySection.getOrDefault("macro", List.of());
            if (primary.isBlank()) {
                return macroItems;
            }
            List<Map<String, Object>> local = new ArrayList<>();
            for (Map<String, Object> item : macroItems) {
                String scope = str(item.get("context_scope")).toLowerCase(Locale.ROOT);
                String country = itemContextCountry(item);
                if (scope.isBlank() || Set.of("primary", "macro").contains(scope) || primary.equals(country)) {
                    local.add(item);
                }
            }
            return local.isEmpty() ? macroItems : local;
        }
        return bySection.getOrDefault(sid, List.of());
    }

    public static boolean isRelatedSectorItem(Map<String, Object> item) {
        if (Boolean.TRUE.equals(item.get("from_related_segment"))) {
            String bucket = fetchItemBucket(item);
            if (Set.of("company", "sector").contains(bucket)) {
                String linked = str(item.get("linked_sector_id"));
                String primary = str(item.get("primary_sector_id"));
                return linked.isBlank() || primary.isBlank() || !linked.equals(primary);
            }
        }
        return false;
    }

    public static String fetchItemBucket(Map<String, Object> item) {
        String role = str(item.get("summarize_role")).toLowerCase(Locale.ROOT);
        String scope = str(item.get("context_scope")).toLowerCase(Locale.ROOT);
        String category = str(item.get("manager_category")).toLowerCase(Locale.ROOT);
        if ("company".equals(role) || "company".equals(scope)) {
            return "company";
        }
        if ("sector".equals(role) || "sector".equals(scope)) {
            return "sector";
        }
        if (Set.of("financial_markets", "market").contains(role) || Set.of("financial_markets", "market").contains(scope)) {
            return "financial_markets";
        }
        if ("commodity".equals(role) || "commodity".equals(scope)) {
            return "commodity";
        }
        if ("demographics".equals(role) || "demographics".equals(scope)) {
            return "demographics";
        }
        if ("fx".equals(role) || "fx".equals(scope)) {
            return "fx";
        }
        if ("partner".equals(role) || "partner".equals(scope)) {
            return "partner";
        }
        if ("neighbor".equals(role) || "neighbor".equals(scope)) {
            return "neighbor";
        }
        if ("eu".equals(role) || "eu".equals(scope)) {
            return "eu";
        }
        if ("continent".equals(role) || "continent".equals(scope)) {
            return "continent";
        }
        if ("global".equals(role) || "global".equals(scope)) {
            return "global";
        }
        if (category.contains("sector") || Boolean.TRUE.equals(item.get("from_preset"))) {
            return "sector";
        }
        if (category.contains("financial") || category.contains("market")) {
            return "financial_markets";
        }
        if (category.contains("commod") || category.contains("energy") || category.contains("cost")) {
            return "commodity";
        }
        if (category.contains("demograph")) {
            return "demographics";
        }
        if (category.contains("macro") || category.contains("forecast")) {
            return "macro";
        }
        String src = str(item.get("source_type")).toLowerCase(Locale.ROOT);
        if (src.contains("financial") || src.contains("fred") || src.contains("alphavantage")) {
            return "financial_markets";
        }
        if (src.contains("commod") || src.contains("pink_sheet")) {
            return "commodity";
        }
        if (MACRO_SOURCES.contains(src)) {
            return "macro";
        }
        return "sector";
    }

    public static String sectionContextBullets(List<Map<String, Object>> items, int maxChars) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> item : items) {
            sb.append("- ").append(item.getOrDefault("data_context_line", item.get("title"))).append("\n");
        }
        String text = sb.toString().trim();
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, Math.max(0, maxChars - 12)).trim() + "\n- …(zkráceno)";
    }

    private static boolean geoConflictsWithPrimary(Map<String, Object> item, String primaryCountryCode) {
        String primary = str(primaryCountryCode).toUpperCase(Locale.ROOT);
        if (primary.length() != 2) {
            return false;
        }
        String country = itemContextCountry(item);
        return !country.isBlank() && country.length() == 2 && !primary.equals(country);
    }

    private static String itemContextCountry(Map<String, Object> item) {
        Object qpObj = item.get("query_params");
        if (qpObj instanceof Map<?, ?> qp) {
            for (String key : List.of("geo", "country")) {
                String val = str(qp.get(key)).toUpperCase(Locale.ROOT);
                if (!val.isBlank()) {
                    return val;
                }
            }
        }
        return str(firstNonBlank(item.get("context_country"), item.get("country"))).toUpperCase(Locale.ROOT);
    }

    @SafeVarargs
    private static List<Map<String, Object>> merge(List<Map<String, Object>>... lists) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (List<Map<String, Object>> list : lists) {
            if (list != null) {
                out.addAll(list);
            }
        }
        return out;
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    private static String firstNonBlank(Object... values) {
        for (Object value : values) {
            String text = str(value);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }
}
