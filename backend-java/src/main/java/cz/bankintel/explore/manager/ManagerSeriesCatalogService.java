package cz.bankintel.explore.manager;
import cz.bankintel.util.BankIntelEnvVars;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.util.BankIntelDataPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Loads manager segment/macro JSON catalogs from shared data dir. */
@Service
@RequiredArgsConstructor
public class ManagerSeriesCatalogService {

    private static final Map<String, Integer> SEGMENT_TIER_ORDER =
            Map.of("must_have", 0, "medium", 1, "minimal", 2);
    private static final Map<String, Integer> MACRO_TIER_ORDER = Map.of(
            "must_have_macro_core", 0,
            "must_have_for_high_impact_sectors", 1,
            "medium_macro_context", 2,
            "minimal_or_specialist_macro", 3);

    private final ObjectMapper objectMapper;
    private final AtomicReference<CatalogIndex> indexRef = new AtomicReference<>();

    public List<Map<String, Object>> segmentRowsForId(String segmentId, boolean specialist) {
        CatalogIndex index = loadIndex();
        String sid = segmentId != null ? segmentId.trim() : "";
        if (sid.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        if (specialist) {
            rows.addAll(index.specialistBySegment.getOrDefault(sid, List.of()));
        } else {
            rows.addAll(index.segmentById.getOrDefault(sid, List.of()));
        }
        rows.sort(
                Comparator.comparingInt((Map<String, Object> row) ->
                                parseInt(row.get("final_priority"), 9999))
                        .thenComparingInt(row -> parseInt(row.get("final_rank_in_segment"), 9999))
                        .thenComparing(row -> -parseDouble(row.get("hybrid_score"))));
        return rows;
    }

    public List<Map<String, Object>> macroAllSeriesRows(boolean specialist) {
        CatalogIndex index = loadIndex();
        if (specialist && !index.macroSpecialist.isEmpty()) {
            return index.macroSpecialist;
        }
        return index.macroSeries;
    }

    public static boolean macroMatchesSector(Map<String, Object> row, String segmentId) {
        if (segmentId == null || segmentId.isBlank()) {
            return true;
        }
        List<String> impact = parseSectorIdList(row.get("macro_high_impact_sector_ids"));
        List<String> relevant = parseSectorIdList(row.get("macro_relevant_sector_ids"));
        if (impact.contains("ALL_20_MANAGER_SECTORS") || relevant.contains("ALL_20_MANAGER_SECTORS")) {
            return true;
        }
        return impact.contains(segmentId) || relevant.contains(segmentId);
    }

    public List<Map<String, Object>> sortSegmentEntries(List<Map<String, Object>> rows) {
        List<Map<String, Object>> copy = new ArrayList<>(rows);
        copy.sort(
                Comparator.comparingInt((Map<String, Object> row) ->
                                ManagerSeriesRuntimeRules.tierRank(
                                        stringOrBlank(row.get("manager_series_tier")), SEGMENT_TIER_ORDER))
                        .thenComparing(row -> -parseDouble(row.get("manager_importance_score")))
                        .thenComparingInt(row -> parseInt(row.get("final_rank_in_segment"), 9999)));
        return copy;
    }

    public List<Map<String, Object>> sortMacroEntries(List<Map<String, Object>> rows) {
        List<Map<String, Object>> copy = new ArrayList<>(rows);
        copy.sort(
                Comparator.comparingInt((Map<String, Object> row) ->
                                ManagerSeriesRuntimeRules.tierRank(
                                        stringOrBlank(row.get("macro_manager_tier")), MACRO_TIER_ORDER))
                        .thenComparing(row -> -parseDouble(row.get("macro_quality_score")))
                        .thenComparingInt(row -> parseInt(row.get("final_rank"), 9999)));
        return copy;
    }

    public DedupeResult dedupeEntries(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<String> dupes = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            String key = ManagerSeriesRuntimeRules.seriesDedupeKey(row);
            if (seen.contains(key)) {
                dupes.add(key);
                continue;
            }
            seen.add(key);
            out.add(row);
        }
        return new DedupeResult(out, dupes);
    }

    private CatalogIndex loadIndex() {
        CatalogIndex cached = indexRef.get();
        if (cached != null) {
            return cached;
        }
        synchronized (indexRef) {
            cached = indexRef.get();
            if (cached != null) {
                return cached;
            }
            cached = readCatalogIndex();
            indexRef.set(cached);
            return cached;
        }
    }

    @SuppressWarnings("unchecked")
    private CatalogIndex readCatalogIndex() {
        Map<String, List<Map<String, Object>>> segmentById = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> specialistBySegment = new LinkedHashMap<>();
        List<Map<String, Object>> macroSeries = List.of();
        List<Map<String, Object>> macroSpecialist = List.of();

        Path segmentPath = resolveSegmentPath();
        if (Files.isRegularFile(segmentPath)) {
            try {
                Map<String, Object> raw = objectMapper.readValue(segmentPath.toFile(), new TypeReference<>() {});
                for (Map<String, Object> block : listOfMaps(raw.get("segments"))) {
                    String sid = stringOrBlank(block.get("segment_id"));
                    if (sid.isBlank()) {
                        continue;
                    }
                    segmentById.put(sid, listOfMaps(block.get("series")));
                }
                for (Map<String, Object> block : listOfMaps(raw.get("specialist_segments"))) {
                    String sid = stringOrBlank(block.get("segment_id"));
                    if (sid.isBlank()) {
                        continue;
                    }
                    specialistBySegment.put(sid, listOfMaps(block.get("series")));
                }
            } catch (IOException ex) {
                // keep empty index
            }
        }

        Path macroPath = resolveMacroPath();
        if (Files.isRegularFile(macroPath)) {
            try {
                Map<String, Object> raw = objectMapper.readValue(macroPath.toFile(), new TypeReference<>() {});
                macroSeries = listOfMaps(raw.get("series"));
                macroSpecialist = listOfMaps(raw.get("specialist_series"));
            } catch (IOException ex) {
                // keep empty
            }
        }

        return new CatalogIndex(segmentById, specialistBySegment, macroSeries, macroSpecialist);
    }

    private static Path resolveSegmentPath() {
        String configured = BankIntelEnvVars.get("MANAGER_SEGMENT_CATALOG_PATH");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        Path primary = BankIntelDataPaths.dataDir().resolve("final_segment_series_selection_manager_tiers.json");
        if (Files.isRegularFile(primary)) {
            return primary;
        }
        return BankIntelDataPaths.dataDir().resolve("segment_series_assignments.json");
    }

    private static Path resolveMacroPath() {
        String configured = BankIntelEnvVars.get("MANAGER_MACRO_CATALOG_PATH");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        Path primary = BankIntelDataPaths.dataDir().resolve("final_macro_series_selection_sector_relevance.json");
        if (Files.isRegularFile(primary)) {
            return primary;
        }
        return BankIntelDataPaths.dataDir().resolve("macro_context_series.json");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
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

    private static List<String> parseSectorIdList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            return list.stream().map(v -> String.valueOf(v).trim()).filter(s -> !s.isBlank()).toList();
        }
        String text = String.valueOf(raw).trim();
        if (text.isBlank()) {
            return List.of();
        }
        if (text.toUpperCase(Locale.ROOT).equals("ALL_20_MANAGER_SECTORS")) {
            return List.of("ALL_20_MANAGER_SECTORS");
        }
        return java.util.Arrays.stream(text.split("[,;]"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
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

    private static String stringOrBlank(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    private record CatalogIndex(
            Map<String, List<Map<String, Object>>> segmentById,
            Map<String, List<Map<String, Object>>> specialistBySegment,
            List<Map<String, Object>> macroSeries,
            List<Map<String, Object>> macroSpecialist) {}

    public record DedupeResult(List<Map<String, Object>> rows, List<String> duplicateKeys) {}
}
