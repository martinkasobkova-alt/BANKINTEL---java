package cz.bankintel.explore.manager.refresh;

import cz.bankintel.explore.EuMembership;
import cz.bankintel.explore.manager.refresh.ManagerEurostatCacheRefreshService.RefreshReport;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Backs the admin-triggered Eurostat refresh endpoint. A full run (~8,800 targets, Phase 2
 * scope) is estimated at 1-3 hours - far too long for a single HTTP request - so this starts
 * the refresh on a virtual thread and hands back a {@code runId} immediately; progress/result
 * is polled via {@link #status}. No new job-queue infrastructure: a simple in-memory map is
 * enough for an admin-only, one-at-a-time diagnostic tool.
 */
@Service
@RequiredArgsConstructor
public class AdminManagerEurostatRefreshService {

    private static final Logger log = LoggerFactory.getLogger(AdminManagerEurostatRefreshService.class);
    private static final String DEFAULT_COLLECTION = "manager_series_cache";

    private final ManagerEurostatCacheRefreshService refreshService;
    private final Executor runExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, RunStatus> runs = new ConcurrentHashMap<>();

    public record RunStatus(String runId, String state, Map<String, Object> request, RefreshReport report, String error) {}

    public String startRefresh(Collection<String> segments, Collection<String> geo, boolean dryRun, String targetCollection) {
        String runId = UUID.randomUUID().toString().replace("-", "");
        Set<String> resolvedGeo = resolveGeo(geo);
        String collectionName = targetCollection == null || targetCollection.isBlank() ? DEFAULT_COLLECTION : targetCollection.trim();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("segments", segments == null ? List.of() : List.copyOf(segments));
        request.put("geo", List.copyOf(resolvedGeo));
        request.put("dry_run", dryRun);
        request.put("target_collection", collectionName);
        runs.put(runId, new RunStatus(runId, "running", request, null, null));
        runExecutor.execute(() -> {
            try {
                RefreshReport report = refreshService.refresh(segments, resolvedGeo, dryRun, collectionName);
                runs.put(runId, new RunStatus(runId, "completed", request, report, null));
            } catch (Exception ex) {
                log.warn("manager eurostat cache refresh run {} failed: {}", runId, ex.getMessage(), ex);
                runs.put(runId, new RunStatus(runId, "failed", request, null, ex.getMessage()));
            }
        });
        return runId;
    }

    public Map<String, Object> status(String runId) {
        RunStatus status = runs.get(runId);
        if (status == null) {
            return Map.of("ok", false, "error", "run_not_found");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("run_id", status.runId());
        out.put("state", status.state());
        out.put("request", status.request());
        if (status.report() != null) {
            RefreshReport r = status.report();
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("targets", r.targets());
            report.put("loaded", r.loaded());
            report.put("unavailable", r.unavailable());
            report.put("stale_suspicious", r.staleSuspicious());
            report.put("written", r.written());
            report.put("wave_status", r.waveStatus());
            report.put("stop_reasons", r.stopReasons());
            out.put("report", report);
        }
        if (status.error() != null) {
            out.put("error", status.error());
        }
        return out;
    }

    /** Bare geo tokens are passed through uppercased as-is; the sole special case is the literal
     * token {@code "EU27"}, which expands to the full {@link EuMembership#ISO2_CODES} set - a
     * convenience for triggering a full run without spelling out all 27 codes by hand. */
    private static Set<String> resolveGeo(Collection<String> geo) {
        if (geo == null || geo.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String value : geo) {
            String upper = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
            if (upper.isBlank()) {
                continue;
            }
            if ("EU27".equals(upper)) {
                out.addAll(EuMembership.ISO2_CODES);
            } else {
                out.add(upper);
            }
        }
        return out;
    }
}
