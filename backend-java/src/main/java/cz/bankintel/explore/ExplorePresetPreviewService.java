package cz.bankintel.explore;

import cz.bankintel.explore.manager.ManagerAnalysisPlanService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Builds json_curated_preview payloads from manager analysis plan (Python _build_json_seed_preview_payload). */
@Service
@RequiredArgsConstructor
public class ExplorePresetPreviewService {

    private static final Set<String> PREVIEW_CATEGORIES = Set.of(
            "sector_indicators",
            "leading_indicators",
            "macro_indicators",
            "financial_indicators",
            "cost_indicators",
            "external_indicators",
            "risk_indicators",
            "forecast_indicators");

    private final ManagerAnalysisPlanService analysisPlanService;

    public Map<String, Object> buildJsonSeedPreview(Map<String, Object> ctx, boolean includeMacro) {
        Map<String, Object> geo = castMap(ctx.get("geo"));
        String managerQuestion = firstNonBlank(ctx.get("manager_question"), ctx.get("sector"));
        String sector = stringOrBlank(ctx.get("sector"));
        String analysisMode = stringOrBlank(ctx.get("analysis_mode"));
        if (analysisMode.isBlank()) {
            analysisMode = ManagerAnalysisPlanService.ANALYSIS_MODE_SECTOR;
        }
        if (!Set.of(ManagerAnalysisPlanService.ANALYSIS_MODE_SECTOR, ManagerAnalysisPlanService.ANALYSIS_MODE_MACRO)
                .contains(analysisMode)) {
            analysisMode = sector.isBlank()
                    ? ManagerAnalysisPlanService.ANALYSIS_MODE_MACRO
                    : ManagerAnalysisPlanService.ANALYSIS_MODE_SECTOR;
        }

        @SuppressWarnings("unchecked")
        List<String> geoCountryCodes = geo.get("country_codes") instanceof List<?> list
                ? list.stream().map(String::valueOf).map(String::trim).filter(s -> !s.isBlank()).map(String::toUpperCase).toList()
                : List.of();
        String countryRaw = firstNonBlank(ctx.get("country"), ctx.get("country_for_query"));
        if (countryRaw.isBlank() && !geoCountryCodes.isEmpty()) {
            countryRaw = String.join(", ", geoCountryCodes);
        }

        Map<String, Object> plan = analysisPlanService.buildManagerAnalysisPlan(
                analysisMode,
                managerQuestion.isBlank() ? (sector.isBlank() ? "Manažerská analýza" : sector) : managerQuestion,
                sector,
                countryRaw,
                stringOrBlank(ctx.get("geo_mode")).isBlank() ? stringOrBlank(geo.get("mode")) : stringOrBlank(ctx.get("geo_mode")),
                stringOrBlank(ctx.get("continent")).isBlank() ? stringOrBlank(geo.get("continent_id")) : stringOrBlank(ctx.get("continent")),
                parseRelatedOverrides(ctx.get("related_segments_list")),
                List.of(),
                null);

        Map<String, List<Map<String, Object>>> groups = emptyPreviewGroups();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        if (!Boolean.TRUE.equals(plan.get("ok"))) {
            Map<String, Object> base = ExploreSectorContract.buildEmptyContract(ctx, "json_curated_preview", true);
            Map<String, Object> failed = new LinkedHashMap<>(base);
            failed.put("ok", false);
            failed.put("error", plan.getOrDefault("error", "Nepodařilo se sestavit analysis plan."));
            failed.put("discovery_source", "json_curated_preview");
            failed.put("discovery_fallback_reason", "analysis_plan_failed");
            return failed;
        }

        appendPreviewIndicators(groups, seen, castList(plan.get("sector_series_refs")));
        if (includeMacro) {
            for (Map<String, Object> item : castList(plan.get("macro_series_refs"))) {
                Map<String, Object> copy = new LinkedHashMap<>(item);
                copy.putIfAbsent("manager_category", "macro_indicators");
                appendPreviewIndicator(groups, seen, copy);
            }
        }
        for (List<Map<String, Object>> bucket : groups.values()) {
            bucket.sort((a, b) -> Double.compare(
                    parseDouble(b.get("confidence_score")), parseDouble(a.get("confidence_score"))));
        }

        List<Map<String, Object>> dependent = new ArrayList<>();
        for (String sid : castStringList(plan.get("related_segments"))) {
            dependent.add(Map.of("sector_id", sid, "sector_name_cs", sid));
        }
        int total = groups.values().stream().mapToInt(List::size).sum();
        List<String> topSources = groups.values().stream()
                .flatMap(list -> list.stream().limit(8))
                .map(row -> stringOrBlank(row.get("source")).toLowerCase())
                .filter(s -> !s.isBlank())
                .distinct()
                .limit(12)
                .toList();

        Map<String, Object> merged = ExploreSectorContract.mergeIndicators(
                ExploreSectorContract.buildEmptyContract(ctx, "json_curated_preview", true),
                groups.get("sector_indicators"),
                groups.get("macro_indicators"),
                total,
                "json_seed",
                true);
        merged.put("ok", true);
        merged.put("partial", true);
        merged.put("discovery_source", "json_curated_preview");
        merged.put("deep_search_status", "json_seed");
        merged.put("top_sources_used", topSources);
        merged.put("dependent_sectors", dependent);
        merged.put("dependent_sector_names_cs", dependent.stream().map(row -> stringOrBlank(row.get("sector_name_cs"))).toList());
        merged.put("sector_indicators_total", groups.get("sector_indicators").size());
        merged.put("macro_indicators_total", groups.get("macro_indicators").size());
        merged.put("total_candidates", total);
        merged.put("index_hits", total);
        merged.put(
                "analysis_plan",
                Map.of(
                        "selection_explanation", plan.get("selection_explanation"),
                        "selection_stats", plan.get("selection_stats"),
                        "dedupe_report", plan.get("dedupe_report")));
        return merged;
    }

    private static Map<String, List<Map<String, Object>>> emptyPreviewGroups() {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (String key : PREVIEW_CATEGORIES) {
            groups.put(key, new ArrayList<>());
        }
        return groups;
    }

    private static void appendPreviewIndicators(
            Map<String, List<Map<String, Object>>> groups, Set<String> seen, List<Map<String, Object>> items) {
        for (Map<String, Object> item : items) {
            appendPreviewIndicator(groups, seen, item);
        }
    }

    private static void appendPreviewIndicator(
            Map<String, List<Map<String, Object>>> groups, Set<String> seen, Map<String, Object> item) {
        if (item == null || item.isEmpty()) {
            return;
        }
        String source = stringOrBlank(item.get("source")).toLowerCase();
        String datasetId = stringOrBlank(item.get("dataset_id"));
        String category = stringOrBlank(item.get("manager_category"));
        if (source.isBlank() || datasetId.isBlank() || !groups.containsKey(category)) {
            category = "sector_indicators";
        }
        if (source.isBlank() || datasetId.isBlank()) {
            return;
        }
        String key = source + "|" + datasetId + "|" + stringOrBlank(item.get("series_id"));
        if (seen.contains(key)) {
            return;
        }
        seen.add(key);
        groups.get(category).add(new LinkedHashMap<>(item));
        if (Boolean.TRUE.equals(item.get("is_forecast"))) {
            String fcKey = key + ":forecast";
            if (!seen.contains(fcKey)) {
                seen.add(fcKey);
                Map<String, Object> fcItem = new LinkedHashMap<>(item);
                fcItem.put("manager_category", "forecast_indicators");
                groups.get("forecast_indicators").add(fcItem);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                out.add((Map<String, Object>) map);
            }
        }
        return out;
    }

    private static List<String> castStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).map(String::trim).filter(s -> !s.isBlank()).toList();
    }

    private static List<String> parseRelatedOverrides(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).map(String::trim).filter(s -> !s.isBlank()).toList();
        }
        return java.util.Arrays.stream(String.valueOf(value).split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    private static String stringOrBlank(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    private static String firstNonBlank(Object... values) {
        for (Object value : values) {
            String text = stringOrBlank(value);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private static double parseDouble(Object value) {
        try {
            return value != null ? Double.parseDouble(String.valueOf(value).trim()) : 0.0;
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }
}
