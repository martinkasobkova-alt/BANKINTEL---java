package cz.bankintel.explore.manager;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.explore.ExploreGeoCatalog;
import cz.bankintel.util.BankIntelDataPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Port of {@code services/manager_relationships.py} for segment resolution and related segments. */
@Service
@RequiredArgsConstructor
public class ManagerRelationshipsService {

    private final ObjectMapper objectMapper;
    private final ExploreGeoCatalog geoCatalog;
    private final AtomicReference<Map<String, Map<String, Object>>> bySegmentIdRef = new AtomicReference<>();

    public String resolveRelationshipSegmentId(String sector) {
        String raw = sector != null ? sector.trim() : "";
        if (raw.isBlank()) {
            return "";
        }
        Map<String, Map<String, Object>> byId = loadBySegmentId();
        Map<String, Object> direct = byId.get(raw);
        if (direct != null) {
            return stringOrBlank(direct.get("segment_id"));
        }
        Map<String, Object> preset = geoCatalog.findSectorByIdOrLabel(raw);
        if (!preset.isEmpty()) {
            String segmentId = stringOrBlank(preset.get("id"));
            if (byId.containsKey(segmentId)) {
                return segmentId;
            }
        }
        String needle = fold(raw);
        for (Map.Entry<String, Map<String, Object>> entry : byId.entrySet()) {
            Map<String, Object> candidate = entry.getValue();
            String nameCs = fold(candidate.get("segment_name_cs"));
            if (needle.equals(fold(entry.getKey())) || needle.equals(nameCs) || nameCs.contains(needle) || needle.contains(nameCs)) {
                return entry.getKey();
            }
        }
        return "";
    }

    public List<String> relatedSegmentsForManager(String sector, List<String> existingSegments, int limit) {
        String segmentId = resolveRelationshipSegmentId(sector);
        Map<String, Object> entry = loadBySegmentId().get(segmentId);
        if (entry == null) {
            return List.of();
        }
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (String item : existingSegments != null ? existingSegments : List.<String>of()) {
            if (item != null && !item.isBlank()) {
                seen.add(fold(item));
            }
        }
        seen.add(fold(entry.get("segment_name_cs")));
        List<String> out = new ArrayList<>();
        for (Object group : List.of(
                entry.get("default_related_segment_names_cs"), entry.get("conditional_related_segment_names_cs"))) {
            if (!(group instanceof List<?> list)) {
                continue;
            }
            for (Object item : list) {
                String label = stringOrBlank(item);
                if (label.isBlank() || seen.contains(fold(label))) {
                    continue;
                }
                seen.add(fold(label));
                out.add(label);
                if (out.size() >= Math.max(1, limit)) {
                    return out;
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> linkedSectorRows(String segmentId) {
        Map<String, Object> entry = loadBySegmentId().get(segmentId);
        if (entry == null) {
            return List.of();
        }
        Object rows = entry.get("related_segments");
        if (!(rows instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                out.add((Map<String, Object>) map);
            }
        }
        out.sort(
                java.util.Comparator.comparingInt((Map<String, Object> row) -> parseInt(row.get("rank"), 9999))
                        .thenComparing(row -> -parseDouble(row.get("weight"))));
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> loadBySegmentId() {
        Map<String, Map<String, Object>> cached = bySegmentIdRef.get();
        if (cached != null) {
            return cached;
        }
        synchronized (bySegmentIdRef) {
            cached = bySegmentIdRef.get();
            if (cached != null) {
                return cached;
            }
            cached = readRelationships();
            bySegmentIdRef.set(cached);
            return cached;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> readRelationships() {
        Path path = BankIntelDataPaths.dataDir().resolve("manager_segment_country_relationships.json");
        if (!Files.isRegularFile(path)) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(path.toFile(), new TypeReference<>() {});
            Object rel = raw.get("segment_relationships");
            if (!(rel instanceof Map<?, ?> relMap)) {
                return Map.of();
            }
            Object byId = relMap.get("by_segment_id");
            if (!(byId instanceof Map<?, ?> map)) {
                return Map.of();
            }
            Map<String, Map<String, Object>> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> value) {
                    out.put(String.valueOf(entry.getKey()), (Map<String, Object>) value);
                }
            }
            return out;
        } catch (IOException ex) {
            return Map.of();
        }
    }

    private static String fold(Object value) {
        return value != null ? String.valueOf(value).trim().toLowerCase(Locale.ROOT) : "";
    }

    private static String stringOrBlank(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    private static int parseInt(Object value, int fallback) {
        try {
            return value != null ? Integer.parseInt(String.valueOf(value).trim()) : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static double parseDouble(Object value) {
        try {
            return value != null ? Double.parseDouble(String.valueOf(value).trim()) : 0.0;
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }
}
