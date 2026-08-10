package cz.bankintel.explore.manager;

import cz.bankintel.explore.ExploreGeoResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Port of {@code services/manager_analysis_plan.py::build_manager_analysis_plan}. */
@Service
@RequiredArgsConstructor
public class ManagerAnalysisPlanService {

    public static final String ANALYSIS_MODE_SECTOR = "sector";
    public static final String ANALYSIS_MODE_MACRO = "macro";

    private static final int NORMAL_SECTOR_LIMIT = 40;
    private static final int BROAD_SECTOR_LIMIT = 80;
    private static final int NORMAL_MACRO_LIMIT = 35;
    private static final int BROAD_MACRO_LIMIT = 60;
    private static final int PRIMARY_SEGMENT_NO_LIMIT = 10_000;
    private static final int RELATED_SEGMENT_MAX_SERIES = 4;
    private static final Set<String> RELATED_SEGMENT_ALLOWED_TIERS = Set.of("must_have");

    private final ExploreGeoResolver geoResolver;
    private final ManagerRelationshipsService relationshipsService;
    private final ManagerSeriesCatalogService catalogService;

    public Map<String, Object> buildManagerAnalysisPlan(
            String analysisMode,
            String question,
            String sector,
            String country,
            String geoMode,
            String continent,
            List<String> relatedSegmentOverrides,
            List<String> uploadIds,
            String companyId) {
        String mode = analysisMode != null ? analysisMode.trim().toLowerCase(Locale.ROOT) : ANALYSIS_MODE_SECTOR;
        if (!Set.of(ANALYSIS_MODE_SECTOR, ANALYSIS_MODE_MACRO).contains(mode)) {
            mode = ANALYSIS_MODE_SECTOR;
        }

        Map<String, Object> geo = geoResolver.resolve(country, geoMode, continent);
        @SuppressWarnings("unchecked")
        List<String> countryCodes = geo.get("country_codes") instanceof List<?> list
                ? (List<String>) list
                : List.of();
        String primaryCode = stringOrBlank(geo.get("primary_code")).toUpperCase(Locale.ROOT);
        if (primaryCode.isBlank() && !countryCodes.isEmpty()) {
            primaryCode = countryCodes.getFirst();
        }
        boolean broad = ManagerSeriesRuntimeRules.questionIsBroad(question, geo);
        int sectorLimit = broad ? BROAD_SECTOR_LIMIT : NORMAL_SECTOR_LIMIT;
        int macroLimit = broad ? BROAD_MACRO_LIMIT : NORMAL_MACRO_LIMIT;

        String segmentId = "";
        List<String> relatedSegments = List.of();
        List<Map<String, Object>> sectorSeriesRefs = List.of();
        List<Map<String, Object>> macroSeriesRefs = List.of();
        List<String> selectionNotes = new ArrayList<>();
        Map<String, Object> dedupeReport = new LinkedHashMap<>();

        if (ANALYSIS_MODE_SECTOR.equals(mode)) {
            segmentId = relationshipsService.resolveRelationshipSegmentId(sector);
            if (segmentId.isBlank()) {
                return Map.of(
                        "ok", false,
                        "error", "Neznámý segment — zadejte jeden z 20 manager segmentů.",
                        "analysis_mode", mode);
            }
            if (relatedSegmentOverrides == null) {
                relatedSegments = relationshipsService.relatedSegmentsForManager(sector, List.of(), 4);
            } else {
                relatedSegments = relatedSegmentOverrides.stream()
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .limit(4)
                        .toList();
            }
            List<String> segmentIds = new ArrayList<>();
            segmentIds.add(segmentId);
            for (String rel : relatedSegments) {
                String rid = relationshipsService.resolveRelationshipSegmentId(rel);
                segmentIds.add(rid.isBlank() ? rel : rid);
            }
            segmentIds = segmentIds.stream().distinct().limit(5).toList();
            final String primarySegmentId = segmentId;
            List<String> relatedIds = segmentIds.stream().filter(id -> !id.equals(primarySegmentId)).toList();

            boolean narrowQuestion = ManagerSeriesRuntimeRules.questionIsNarrow(question);
            List<Map<String, Object>> allTemplates = new ArrayList<>();
            Map<String, Object> tierReport = new LinkedHashMap<>();
            for (String sid : segmentIds) {
                boolean isPrimary = sid.equals(segmentId);
                int segLimit = isPrimary ? PRIMARY_SEGMENT_NO_LIMIT : RELATED_SEGMENT_MAX_SERIES;
                Set<String> allowedTiers = isPrimary
                        ? ManagerSeriesRuntimeRules.allowedSegmentTiers(narrowQuestion, broad)
                        : RELATED_SEGMENT_ALLOWED_TIERS;
                SelectionResult selection = selectSegmentTemplates(
                        sid,
                        question,
                        countryCodes,
                        primaryCode,
                        geo,
                        segLimit,
                        allowedTiers,
                        isPrimary && narrowQuestion);
                tierReport.put(
                        sid,
                        Map.of(
                                "is_related_segment", !isPrimary,
                                "related_tier_policy", isPrimary ? "primary_tier_policy" : "must_have_only",
                                "tier_counts", selection.tierCounts()));
                for (Map<String, Object> row : selection.rows()) {
                    Map<String, Object> tagged = new LinkedHashMap<>(row);
                    tagged.put("_plan_segment_id", sid);
                    allTemplates.add(tagged);
                }
            }

            List<Map<String, Object>> refs = new ArrayList<>();
            for (Map<String, Object> row : allTemplates) {
                String srcSid = stringOrBlank(row.get("_plan_segment_id"));
                if (srcSid.isBlank()) {
                    srcSid = segmentId;
                }
                Map<String, Object> ref = entryToRef(row, srcSid);
                if (!srcSid.equals(segmentId)) {
                    ref.put("from_related_segment", true);
                    ref.put("linked_sector_id", srcSid);
                    ref.put("primary_sector_id", segmentId);
                }
                refs.add(ref);
            }
            ManagerSeriesCatalogService.DedupeResult sectorDedupe = catalogService.dedupeEntries(expandForGeo(refs, geo, broad));
            sectorSeriesRefs = sectorDedupe.rows();
            sectorSeriesRefs = applyRelatedTierCap(sectorSeriesRefs, relatedIds.size(), selectionNotes);

            ManagerSeriesCatalogService.DedupeResult macroDedupe =
                    catalogService.dedupeEntries(selectMacroRefs(segmentId, question, countryCodes, primaryCode, geo, macroLimit, narrowQuestion));
            macroSeriesRefs = macroDedupe.rows();

            dedupeReport.put("sector_duplicate_keys", sectorDedupe.duplicateKeys().stream().limit(20).toList());
            dedupeReport.put("macro_duplicate_keys", macroDedupe.duplicateKeys().stream().limit(20).toList());
            dedupeReport.put("segment_tier_report", tierReport);
            if (!relatedIds.isEmpty()) {
                selectionNotes.add(
                        "Související odvětví ("
                                + relatedIds.size()
                                + "): max "
                                + RELATED_SEGMENT_MAX_SERIES
                                + " řad/segment, pouze tier must_have z Excelu.");
            }
        } else {
            selectionNotes.add("Režim makro-only: segmentové řady a related segments se nepoužívají.");
            ManagerSeriesCatalogService.DedupeResult macroDedupe =
                    catalogService.dedupeEntries(selectMacroRefs(null, question, countryCodes, primaryCode, geo, macroLimit, false));
            macroSeriesRefs = macroDedupe.rows();
            dedupeReport.put("macro_duplicate_keys", macroDedupe.duplicateKeys().stream().limit(20).toList());
        }

        Map<String, Integer> tierCounts = tierCounts(sectorSeriesRefs);
        Map<String, Integer> relatedTierCounts = relatedTierCounts(sectorSeriesRefs);
        List<String> explanation = new ArrayList<>();
        explanation.add("Režim: " + mode + ".");
        explanation.add("Geo: " + geo.getOrDefault("display", geo.get("mode")) + ".");
        explanation.add(
                "Sektorové řady: "
                        + sectorSeriesRefs.size()
                        + " (must_have="
                        + tierCounts.getOrDefault("must_have", 0)
                        + ", medium="
                        + tierCounts.getOrDefault("medium", 0)
                        + ", minimal="
                        + tierCounts.getOrDefault("minimal", 0)
                        + ").");
        explanation.add("Makro řady: " + macroSeriesRefs.size() + ".");
        explanation.addAll(selectionNotes);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("analysis_mode", mode);
        out.put("question", question);
        out.put("geo", geo);
        if (!segmentId.isBlank()) {
            out.put(
                    "primary_segment",
                    Map.of(
                            "segment_id", segmentId,
                            "sector_label", sector));
        } else {
            out.put("primary_segment", null);
        }
        out.put("related_segments", relatedSegments);
        // continent_id and company_id may be null (geo_mode=country/countries/unknown, optional company scope) —
        // Map.of() rejects null values, so use LinkedHashMap to keep these keys present with null in the JSON.
        Map<String, Object> countryContext = new LinkedHashMap<>();
        countryContext.put("country_codes", countryCodes);
        countryContext.put("primary_code", primaryCode);
        countryContext.put("continent_id", geo.get("continent_id"));
        out.put("country_context", countryContext);
        out.put("sector_series_refs", sectorSeriesRefs);
        out.put("macro_series_refs", macroSeriesRefs);
        Map<String, Object> userUploadContext = new LinkedHashMap<>();
        userUploadContext.put("upload_ids", uploadIds != null ? uploadIds : List.of());
        userUploadContext.put("company_id", companyId);
        out.put("user_upload_context", userUploadContext);
        out.put("dedupe_report", dedupeReport);
        out.put("selection_explanation", explanation);
        out.put(
                "selection_stats",
                Map.of(
                        "sector_series_count", sectorSeriesRefs.size(),
                        "macro_series_count", macroSeriesRefs.size(),
                        "tier_counts", tierCounts,
                        "related_tier_counts", relatedTierCounts,
                        "related_series_count",
                                sectorSeriesRefs.stream().filter(row -> Boolean.TRUE.equals(row.get("from_related_segment"))).count(),
                        "related_tier_policy", "must_have_only",
                        "broad_analysis", broad));
        return out;
    }

    private SelectionResult selectSegmentTemplates(
            String segmentId,
            String question,
            List<String> countryCodes,
            String primaryCountryCode,
            Map<String, Object> geo,
            int limit,
            Set<String> allowedTiers,
            boolean useSpecialistSheet) {
        List<Map<String, Object>> rows = catalogService.segmentRowsForId(segmentId, useSpecialistSheet);
        List<Map<String, Object>> picked = new ArrayList<>();
        Map<String, Integer> stats = new LinkedHashMap<>();
        stats.put("must_have", 0);
        stats.put("medium", 0);
        stats.put("minimal", 0);
        stats.put("skipped_geo", 0);
        stats.put("skipped_tier", 0);
        for (Map<String, Object> entry : catalogService.sortSegmentEntries(rows)) {
            String tier = stringOrBlank(entry.get("manager_series_tier")).toLowerCase(Locale.ROOT);
            if (!allowedTiers.contains(tier)) {
                stats.merge("skipped_tier", 1, Integer::sum);
                continue;
            }
            if (!ManagerSeriesRuntimeRules.seriesAllowedForManagerContext(entry, countryCodes, primaryCountryCode, geo)) {
                stats.merge("skipped_geo", 1, Integer::sum);
                continue;
            }
            picked.add(entry);
            stats.merge(tier, 1, Integer::sum);
            if (picked.size() >= limit) {
                break;
            }
        }
        return new SelectionResult(picked, stats);
    }

    private List<Map<String, Object>> selectMacroRefs(
            String segmentId,
            String question,
            List<String> countryCodes,
            String primaryCountryCode,
            Map<String, Object> geo,
            int limit,
            boolean useWatchlist) {
        boolean narrow = ManagerSeriesRuntimeRules.questionIsNarrow(question);
        List<Map<String, Object>> rows = catalogService.macroAllSeriesRows(useWatchlist);
        List<Map<String, Object>> picked = new ArrayList<>();
        for (Map<String, Object> entry : catalogService.sortMacroEntries(rows)) {
            String tier = stringOrBlank(entry.get("macro_manager_tier")).toLowerCase(Locale.ROOT);
            if (!ManagerSeriesRuntimeRules.macroTierAllowed(tier, narrow, useWatchlist)) {
                continue;
            }
            if (segmentId != null
                    && !segmentId.isBlank()
                    && !ManagerSeriesCatalogService.macroMatchesSector(entry, segmentId)
                    && !"must_have_macro_core".equals(tier)) {
                continue;
            }
            if (!ManagerSeriesRuntimeRules.seriesAllowedForManagerContext(entry, countryCodes, primaryCountryCode, geo)) {
                continue;
            }
            picked.add(entryToRef(entry, null));
            if (picked.size() >= limit) {
                break;
            }
        }
        return picked;
    }

    private static List<Map<String, Object>> expandForGeo(
            List<Map<String, Object>> refs, Map<String, Object> geo, boolean broad) {
        if (refs.isEmpty()) {
            return refs;
        }
        @SuppressWarnings("unchecked")
        List<String> countryCodes = geo.get("country_codes") instanceof List<?> list ? (List<String>) list : List.of();
        if (countryCodes.isEmpty()) {
            return refs;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> ref : refs) {
            Map<String, Object> copy = new LinkedHashMap<>(ref);
            copy.put("country_codes", countryCodes);
            copy.put("geo_scope", geo.get("display"));
            copy.put("broad_region_query", broad);
            out.add(copy);
        }
        return out;
    }

    private static List<Map<String, Object>> applyRelatedTierCap(
            List<Map<String, Object>> refs, int relatedSegmentCount, List<String> selectionNotes) {
        List<Map<String, Object>> primary = new ArrayList<>();
        List<Map<String, Object>> related = new ArrayList<>();
        for (Map<String, Object> row : refs) {
            if (Boolean.TRUE.equals(row.get("from_related_segment"))) {
                String tier = stringOrBlank(row.get("manager_series_tier")).toLowerCase(Locale.ROOT);
                if (!RELATED_SEGMENT_ALLOWED_TIERS.contains(tier)) {
                    continue;
                }
                related.add(row);
            } else {
                primary.add(row);
            }
        }
        int relatedCap = relatedSegmentCount * RELATED_SEGMENT_MAX_SERIES;
        if (relatedCap > 0 && related.size() > relatedCap) {
            related = related.subList(0, relatedCap);
            selectionNotes.add("Související odvětví ořezána na " + relatedCap + " kritických (must_have) řad.");
        }
        List<Map<String, Object>> merged = new ArrayList<>(primary);
        merged.addAll(related);
        return merged;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> entryToRef(Map<String, Object> entry, String segmentId) {
        Map<String, Object> ref = new LinkedHashMap<>();
        String source = ManagerSeriesRuntimeRules.normalizeSourceId(entry.get("source"));
        ref.put("source", source);
        ref.put("source_type", source);
        String datasetId = stringOrBlank(entry.get("dataset_id"));
        ref.put("dataset_id", datasetId);
        ref.put("set_id", datasetId);
        ref.put("series_id", stringOrBlank(entry.get("series_id")).isBlank() ? datasetId : stringOrBlank(entry.get("series_id")));
        String title = firstNonBlank(entry.get("title"), entry.get("dataset_name"), entry.get("dataset_id"));
        ref.put("indicator_name", title);
        ref.put("title", title);
        Object qp = entry.get("query_params");
        if (qp == null) {
            qp = entry.get("filters_used");
        }
        ref.put("filters_used", qp instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : Map.of());
        ref.put("query_params", ref.get("filters_used"));
        ref.put(
                "confidence_score",
                parseDouble(entry.get("manager_importance_score")) > 0
                        ? parseDouble(entry.get("manager_importance_score")) / 100.0
                        : parseDouble(entry.get("relevance_score")));
        ref.put("manager_series_tier", entry.get("manager_series_tier"));
        ref.put("macro_manager_tier", entry.get("macro_manager_tier"));
        ref.put("manager_category", segmentId != null ? "sector_indicators" : "macro_indicators");
        ref.put("from_preset", segmentId != null);
        ref.put("default_selected", true);
        ref.put("selection_reason", firstNonBlank(entry.get("manager_tier_reason_cs"), entry.get("match_reason")));
        if (segmentId != null) {
            ref.put("sector_ids", List.of(segmentId));
        }
        Object geo = entry.get("geo");
        if (geo == null) {
            geo = entry.get("countries");
        }
        ref.put("countries", geo instanceof List<?> list ? list : List.of());
        ref.put("geo_scope", ref.get("countries"));
        return ref;
    }

    private static Map<String, Integer> tierCounts(List<Map<String, Object>> refs) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("must_have", 0);
        counts.put("medium", 0);
        counts.put("minimal", 0);
        for (Map<String, Object> row : refs) {
            String tier = stringOrBlank(row.get("manager_series_tier"));
            if (counts.containsKey(tier)) {
                counts.merge(tier, 1, Integer::sum);
            }
        }
        return counts;
    }

    private static Map<String, Integer> relatedTierCounts(List<Map<String, Object>> refs) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("must_have", 0);
        counts.put("medium", 0);
        counts.put("minimal", 0);
        for (Map<String, Object> row : refs) {
            if (!Boolean.TRUE.equals(row.get("from_related_segment"))) {
                continue;
            }
            String tier = stringOrBlank(row.get("manager_series_tier"));
            if (counts.containsKey(tier)) {
                counts.merge(tier, 1, Integer::sum);
            }
        }
        return counts;
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

    private record SelectionResult(List<Map<String, Object>> rows, Map<String, Integer> tierCounts) {}
}
