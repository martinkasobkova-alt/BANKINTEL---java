package cz.bankintel.explore.manager.refresh;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.util.BankIntelDataPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reads the curated Eurostat rows out of the 25 raw Manager Explorer segment bundle files
 * ({@code Bankoapp-main/backend/config/manager_segments/*.json}) - the SAME files the legacy
 * Python refresh script uses, NOT {@code ManagerSeriesCatalogService}'s derived/merged
 * selection file (which lacks per-series {@code query_params} entirely; confirmed by direct
 * comparison during design). Each row already carries a fully-resolved Eurostat dimension
 * spec (nace_r2/indic_bt/s_adj/unit/freq) and a {@code geo_coverage} list of countries that
 * specific series has actually been verified for - the refresh job must trust and reuse this
 * curated data rather than re-deriving dimensions at fetch time.
 */
@Service
@RequiredArgsConstructor
public class ManagerSegmentBundleLoader {

    private static final Logger log = LoggerFactory.getLogger(ManagerSegmentBundleLoader.class);

    /** Statuses that must exclude a row from live refresh - matches the legacy Python script's
     * own gate (services/manager_segment_bundles.py: STALE_STATUSES + {inactive, deprecated,
     * discontinued, reject, rejected}). A BLANK status defaults to "active" (same default the
     * Python side applies), so blank never lands here. */
    private static final Set<String> EXCLUDED_STATUSES =
            Set.of("inactive", "stale", "discontinued", "discontinued_or_stale", "deprecated", "reject", "rejected");

    private final ObjectMapper objectMapper;
    private final AtomicReference<List<Map<String, Object>>> cacheRef = new AtomicReference<>();

    /** All active Eurostat rows across every segment bundle, each stamped with its own
     * {@code segment_id} (added if the row didn't already carry one). Load-once, cached. */
    public List<Map<String, Object>> eurostatRows() {
        List<Map<String, Object>> cached = cacheRef.get();
        if (cached != null) {
            return cached;
        }
        List<Map<String, Object>> loaded = loadEurostatRows();
        cacheRef.set(loaded);
        return loaded;
    }

    /** Convenience filter over {@link #eurostatRows()} for a requested subset of segments;
     * an empty/null {@code segmentIds} means "all segments". */
    public List<Map<String, Object>> eurostatRowsForSegments(Collection<String> segmentIds) {
        if (segmentIds == null || segmentIds.isEmpty()) {
            return eurostatRows();
        }
        Set<String> wanted = Set.copyOf(segmentIds);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : eurostatRows()) {
            if (wanted.contains(str(row.get("segment_id")))) {
                out.add(row);
            }
        }
        return out;
    }

    private List<Map<String, Object>> loadEurostatRows() {
        Path dir = BankIntelDataPaths.managerSegmentBundlesDir();
        if (!Files.isDirectory(dir)) {
            log.warn("manager segment bundles directory not found: {}", dir);
            return List.of();
        }
        List<Path> files;
        try (var stream = Files.list(dir)) {
            files = stream.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
        } catch (IOException ex) {
            log.warn("failed to list manager segment bundles dir {}: {}", dir, ex.getMessage());
            return List.of();
        }
        if (files.isEmpty()) {
            log.warn("manager segment bundles directory has no JSON files: {}", dir);
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Path file : files) {
            out.addAll(loadFile(file));
        }
        if (out.isEmpty()) {
            log.warn("manager segment bundle loader found 0 active eurostat rows across {} files in {}", files.size(), dir);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadFile(Path file) {
        Map<String, Object> root;
        try {
            root = objectMapper.readValue(Files.readString(file), new TypeReference<>() {});
        } catch (IOException ex) {
            log.warn("failed to read manager segment bundle {}: {}", file, ex.getMessage());
            return List.of();
        }
        String segmentId = str(root.get("segment_id"));
        if (segmentId.isBlank()) {
            segmentId = file.getFileName().toString().replaceFirst("\\.json$", "");
        }
        Object seriesObj = root.get("series");
        if (!(seriesObj instanceof List<?> seriesList)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : seriesList) {
            if (!(item instanceof Map<?, ?> rawRow)) {
                continue;
            }
            Map<String, Object> row = (Map<String, Object>) rawRow;
            if (!"eurostat".equalsIgnoreCase(str(row.get("source")))) {
                continue;
            }
            if (isExcludedStatus(row)) {
                continue;
            }
            Map<String, Object> enriched = new LinkedHashMap<>(row);
            enriched.putIfAbsent("segment_id", segmentId);
            out.add(enriched);
        }
        return out;
    }

    private static boolean isExcludedStatus(Map<String, Object> row) {
        String status = str(row.get("status")).toLowerCase(Locale.ROOT);
        return !status.isBlank() && EXCLUDED_STATUSES.contains(status);
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }
}
