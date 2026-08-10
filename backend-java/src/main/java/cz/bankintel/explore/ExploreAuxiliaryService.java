package cz.bankintel.explore;

import cz.bankintel.explore.manager.ManagerRelationshipsService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ExploreAuxiliaryService {

    private final ExploreGeoResolver geoResolver;
    private final ExploreQueryUnderstandingService queryUnderstandingService;
    private final ManagerRelationshipsService managerRelationshipsService;

    public Map<String, Object> relatedSuggestions(
            String sector, String country, String geoMode, String continent, String relatedSegments) {
        if (sector == null || sector.isBlank()) {
            return Map.of("ok", false, "error", "Chybí hlavní segment.", "suggestions", List.of());
        }
        Map<String, Object> geo = geoResolver.resolve(country, geoMode, continent);
        String geoLabel = str(geo.get("display"));
        List<String> existing = new ArrayList<>();
        for (String part : str(relatedSegments).split(",")) {
            if (!part.isBlank()) {
                existing.add(part.trim());
            }
        }
        List<String> suggestions =
                managerRelationshipsService.relatedSegmentsForManager(sector, existing, 6);
        List<Map<String, Object>> relatedRows =
                managerRelationshipsService.linkedSectorRows(managerRelationshipsService.resolveRelationshipSegmentId(sector));
        if (suggestions.isEmpty() && !relatedRows.isEmpty()) {
            suggestions = relatedRows.stream()
                    .map(row -> str(row.get("sector_name_cs")))
                    .filter(s -> !s.isBlank())
                    .limit(6)
                    .toList();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("sector", sector);
        out.put("related_segments", relatedSegments == null || relatedSegments.isBlank() ? null : relatedSegments);
        out.put("geo_display", geoLabel.isBlank() ? null : geoLabel);
        out.put("suggestions", suggestions);
        out.put("related_segment_rows", relatedRows);
        return out;
    }

    public Map<String, Object> countrySuggestions(String sector, String country, String geoMode, String continent) {
        Map<String, Object> geo = geoResolver.resolve(country, geoMode, continent);
        List<Map<String, Object>> suggestions = new ArrayList<>();
        for (Map<String, String> hint : ExploreGeoCatalog.countryHints()) {
            suggestions.add(new LinkedHashMap<>(hint));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("geo", geo);
        out.put("geo_display", geo.get("display"));
        out.put("suggestions", suggestions);
        out.put("sector", sector);
        Object codes = geo.get("country_codes");
        if (codes instanceof List<?> list) {
            out.put("selected_country_codes", list);
            if (!list.isEmpty()) {
                out.put("primary_country_code", list.getFirst());
            }
        }
        return out;
    }

    public Map<String, Object> queryUnderstanding(String query) {
        Map<String, Object> qu = queryUnderstandingService.understand(query, "", "", "", "", true);
        boolean blocked = qu.get("sector") == null || String.valueOf(qu.get("sector")).isBlank();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", !blocked);
        out.put("status", blocked ? "needs_clarification" : "ok");
        out.put("query_understanding", qu);
        out.put("needs_user_confirmation", blocked);
        out.put("clarification", blocked ? Map.of("message", "Upřesněte segment nebo zemi.") : null);
        return out;
    }

    public Map<String, Object> refineSector(Map<String, Object> body) {
        String sector = str(body.get("sector"));
        Map<String, Object> geo = geoResolver.resolve(
                str(body.get("country")), str(body.get("geo_mode")), str(body.get("continent")));
        if ("unknown".equals(String.valueOf(geo.getOrDefault("mode", "")))) {
            return Map.of(
                    "ok", false,
                    "error",
                    "Geo kontext se nepodařilo rozpoznat. Zvolte režim (svět / země / kontinent) nebo zadejte např. Česká republika, CZ, DE nebo Evropa.");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> selected = body.get("selected_series") instanceof List<?> list
                ? (List<Map<String, Object>>) list
                : List.of();
        List<Map<String, Object>> refined = new ArrayList<>();
        for (Map<String, Object> item : selected) {
            Map<String, Object> row = new LinkedHashMap<>(item);
            row.put("geo_scope", geo.get("display"));
            row.put("country_codes", geo.get("country_codes"));
            refined.add(row);
        }
        String segmentId = managerRelationshipsService.resolveRelationshipSegmentId(sector);
        List<Map<String, Object>> ecosystemRows = managerRelationshipsService.linkedSectorRows(segmentId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("sector", sector);
        out.put("geo", geo);
        out.put("refined_series", refined);
        out.put("selected_series", refined);
        out.put("linked_sectors", ecosystemRows);
        out.put("ecosystem_tagged", !ecosystemRows.isEmpty());
        out.put("refine_cache_key", segmentId + "|" + geo.get("mode") + "|" + refined.size());
        return out;
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value).strip() : "";
    }
}
