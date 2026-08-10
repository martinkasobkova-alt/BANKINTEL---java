package cz.bankintel.sources.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.service.platform.BankIntelMaintenanceService;
import cz.bankintel.util.BankIntelDataPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Read macro topics snapshot files — port {@code macro_topics_snapshot.py} read path. */
@Service
public class MacroTopicsSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(MacroTopicsSnapshotService.class);

    private final ObjectMapper objectMapper;
    private final BankIntelMaintenanceService maintenanceService;
    private final Map<String, CacheEntry> partCache = new ConcurrentHashMap<>();

    public MacroTopicsSnapshotService(ObjectMapper objectMapper, BankIntelMaintenanceService maintenanceService) {
        this.objectMapper = objectMapper;
        this.maintenanceService = maintenanceService;
    }

    public boolean hasSnapshot() {
        Path monolith = snapshotPath();
        Path overview = partsDir().resolve("overview.json");
        return Files.isRegularFile(monolith) || Files.isRegularFile(overview);
    }

    /**
     * Nightly rebuild — shells out to Python {@code scripts/build_macro_topics_snapshot.py} when
     * {@code BANKINTEL_PYTHON_ROOT} is available; otherwise logs a placeholder tick.
     */
    public void rebuildPlaceholder() {
        partCache.clear();
        Path monolith = snapshotPath();
        Path parts = partsDir();
        if (maintenanceService.pythonAvailable()) {
            log.info("macro topics snapshot rebuild starting via Python (monolith={} parts_dir={})", monolith, parts);
            try {
                Map<String, Object> result = maintenanceService.runMacroSnapshotRebuild();
                if (Boolean.TRUE.equals(result.get("ok"))) {
                    log.info("macro topics snapshot rebuild SUCCEEDED has_snapshot={}", hasSnapshot());
                } else {
                    log.warn(
                            "macro topics snapshot rebuild FAILED ok={} error={} reason={}",
                            result.get("ok"),
                            result.get("error"),
                            result.get("reason"));
                }
            } catch (Exception ex) {
                log.error("macro topics snapshot rebuild FAILED: {}", ex.getMessage(), ex);
            }
            return;
        }
        log.info(
                "macro topics snapshot placeholder (Python unavailable): has_snapshot={} monolith={} parts_dir={} — "
                        + "set BANKINTEL_PYTHON_ROOT or provision MACRO_TOPICS_SNAPSHOT_PATH manually",
                hasSnapshot(),
                monolith,
                parts);
    }

    public Map<String, Object> getOverview() {
        Map<String, Object> part = loadPart(partsDir().resolve("overview.json"));
        if (part != null) {
            Object overview = part.get("overview");
            if (overview instanceof Map<?, ?> map) {
                return mergeSnapshotMeta(toStringObjectMap(map), part);
            }
        }
        Map<String, Object> snap = loadMonolith();
        if (snap == null) {
            return null;
        }
        Object overview = snap.get("overview");
        if (!(overview instanceof Map<?, ?> map)) {
            return null;
        }
        return mergeSnapshotMeta(toStringObjectMap(map), snap);
    }

    public Map<String, Object> getComparisonTable(boolean onlyComplete, int minColumns, String scope, boolean includeValues) {
        String scopeNorm = normalizeScope(scope);
        Path partPath = "world".equals(scopeNorm)
                ? partsDir().resolve("comparison_world.json")
                : partsDir().resolve("comparison_eu.json");
        Map<String, Object> part = loadPart(partPath);
        String tableKey = "world".equals(scopeNorm) ? "comparison_table_world" : "comparison_table";
        Map<String, Object> table = null;
        if (part != null) {
            Object fromPart = part.get("comparison_table");
            if (fromPart instanceof Map<?, ?> map) {
                table = toStringObjectMap(map);
            } else {
                Object alt = part.get(tableKey);
                if (alt instanceof Map<?, ?> map) {
                    table = toStringObjectMap(map);
                }
            }
        }
        if (table == null) {
            Map<String, Object> snap = loadMonolith();
            if (snap != null) {
                Object fromSnap = snap.get(tableKey);
                if (fromSnap instanceof Map<?, ?> map) {
                    table = toStringObjectMap(map);
                }
            }
        }
        if (table == null) {
            return null;
        }
        Map<String, Object> filtered = filterComparisonTable(table, onlyComplete, minColumns);
        return mergeSnapshotMeta(filtered, part != null ? part : loadMonolith());
    }

    public Map<String, Object> getExtraTables(boolean refresh) {
        if (refresh) {
            return Map.of("ok", false, "message_cs", "Live rebuild extra tables neni v Java backendu — pouzijte Python build.");
        }
        Path path = BankIntelDataPaths.dataDir().resolve("macro_extra_tables_snapshot.json");
        if (!Files.isRegularFile(path)) {
            return Map.of("ok", false, "message_cs", "macro_extra_tables_snapshot.json chybi.");
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(path.toFile(), new TypeReference<>() {});
            return payload != null ? payload : Map.of();
        } catch (IOException ex) {
            return Map.of("ok", false, "error", ex.getMessage());
        }
    }

    public Map<String, Object> getSeriesPreview(String catalogId, String setId, String geo, String topicId) {
        Map<String, Object> snap = loadMonolith();
        if (snap == null) {
            return null;
        }
        Object previewsObj = snap.get("series_previews");
        if (!(previewsObj instanceof Map<?, ?> previews)) {
            return null;
        }
        Map<String, Object> series = new LinkedHashMap<>();
        series.put("catalog_id", catalogId);
        series.put("set_id", setId);
        series.put("geo", geo);
        series.put("topic_id", topicId != null ? topicId : "");
        String key = seriesPreviewKey(series);
        Object hit = previews.get(key);
        if (!(hit instanceof Map<?, ?> map)) {
            if (topicId != null && !topicId.isBlank()) {
                Map<String, Object> altSeries = new LinkedHashMap<>(series);
                altSeries.put("topic_id", "");
                hit = previews.get(seriesPreviewKey(altSeries));
            }
        }
        if (hit instanceof Map<?, ?> map) {
            return mergeSnapshotMeta(toStringObjectMap(map), snap);
        }
        return null;
    }

    public static Map<String, Object> snapshotMeta(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return Map.of(
                    "data_source", "live",
                    "snapshot_generated_at", null,
                    "snapshot_available", false);
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("data_source", "snapshot");
        meta.put("snapshot_available", true);
        meta.put("snapshot_generated_at", payload.get("generated_at"));
        meta.put("snapshot_version", payload.get("version"));
        return meta;
    }

    public static String seriesPreviewKey(Map<String, Object> series) {
        String geo = stringOrBlank(series.get("geo")).toUpperCase(Locale.ROOT);
        String sid = stringOrBlank(series.get("set_id"));
        String src = stringOrBlank(series.get("catalog_id")).toLowerCase(Locale.ROOT);
        if (src.isBlank()) {
            src = stringOrBlank(series.get("source")).toLowerCase(Locale.ROOT);
        }
        String tid = stringOrBlank(series.get("topic_id")).toLowerCase(Locale.ROOT);
        return geo + ":" + tid + ":" + src + ":" + sid;
    }

    private Map<String, Object> loadMonolith() {
        Path path = snapshotPath();
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            return objectMapper.readValue(path.toFile(), new TypeReference<>() {});
        } catch (IOException ex) {
            return null;
        }
    }

    private Map<String, Object> loadPart(Path path) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            long mtime = Files.getLastModifiedTime(path).toMillis();
            String key = path.toString();
            CacheEntry cached = partCache.get(key);
            if (cached != null && cached.mtime() == mtime) {
                return cached.payload();
            }
            Map<String, Object> payload = objectMapper.readValue(path.toFile(), new TypeReference<>() {});
            if (payload != null) {
                partCache.put(key, new CacheEntry(mtime, payload));
            }
            return payload;
        } catch (IOException ex) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> filterComparisonTable(
            Map<String, Object> table, boolean onlyComplete, int minColumns) {
        Map<String, Object> out = new LinkedHashMap<>(table);
        Object groupsObj = table.get("country_groups");
        if (!(groupsObj instanceof List<?> groups)) {
            return out;
        }
        List<Map<String, Object>> filteredGroups = new java.util.ArrayList<>();
        for (Object groupObj : groups) {
            if (!(groupObj instanceof Map<?, ?> groupRaw)) {
                continue;
            }
            Map<String, Object> group = toStringObjectMap(groupRaw);
            Object countriesObj = group.get("countries");
            if (!(countriesObj instanceof List<?> countries)) {
                filteredGroups.add(group);
                continue;
            }
            List<Map<String, Object>> kept = new java.util.ArrayList<>();
            for (Object countryObj : countries) {
                if (!(countryObj instanceof Map<?, ?> countryRaw)) {
                    continue;
                }
                Map<String, Object> country = toStringObjectMap(countryRaw);
                int filled = countFilledCells(country);
                if (filled < minColumns) {
                    continue;
                }
                if (onlyComplete) {
                    Object expected = table.get("column_count");
                    int expectedCount = expected instanceof Number n ? n.intValue() : minColumns;
                    if (filled < expectedCount) {
                        continue;
                    }
                }
                kept.add(country);
            }
            if (!kept.isEmpty()) {
                Map<String, Object> copy = new LinkedHashMap<>(group);
                copy.put("countries", kept);
                filteredGroups.add(copy);
            }
        }
        out.put("country_groups", filteredGroups);
        return out;
    }

    private static int countFilledCells(Map<String, Object> country) {
        Object cellsObj = country.get("cells");
        if (!(cellsObj instanceof Map<?, ?> cells)) {
            return 0;
        }
        int count = 0;
        for (Object cellObj : cells.values()) {
            if (cellObj instanceof Map<?, ?> cell && cell.get("value") != null) {
                count++;
            }
        }
        return count;
    }

    private static Map<String, Object> mergeSnapshotMeta(Map<String, Object> payload, Map<String, Object> snap) {
        Map<String, Object> out = new LinkedHashMap<>(payload);
        out.putAll(snapshotMeta(snap));
        return out;
    }

    private static String normalizeScope(String scope) {
        String raw = stringOrBlank(scope).toLowerCase(Locale.ROOT);
        if ("world".equals(raw) || "svet".equals(raw) || "all".equals(raw)) {
            return "world".equals(raw) || "svet".equals(raw) ? "world" : "all";
        }
        return "eu";
    }

    private static Path snapshotPath() {
        return BankIntelDataPaths.macroTopicsSnapshotPath();
    }

    private static Path partsDir() {
        return BankIntelDataPaths.macroTopicsSnapshotPartsDir();
    }

    private static Map<String, Object> toStringObjectMap(Map<?, ?> input) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    private static String stringOrBlank(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    private record CacheEntry(long mtime, Map<String, Object> payload) {}
}
