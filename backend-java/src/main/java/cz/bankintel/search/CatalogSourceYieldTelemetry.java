package cz.bankintel.search;

import cz.bankintel.search.model.CatalogKeys;
import cz.bankintel.search.model.CatalogMapSupport;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Rolling, source-agnostic observations used to calibrate future lane scheduling decisions. */
@Component
public class CatalogSourceYieldTelemetry {

    private static final int MAX_SERIES = 2_048;
    private static final int WINDOW_SIZE = 64;

    private final ConcurrentHashMap<YieldKey, RollingWindow> windows = new ConcurrentHashMap<>();

    public Map<String, Map<String, Object>> observe(
            String queryShape, List<Map<String, Object>> sourceStatuses) {
        String shape = normalizeShape(queryShape);
        Map<String, Map<String, Object>> snapshots = new LinkedHashMap<>();
        for (Map<String, Object> status : sourceStatuses == null
                ? List.<Map<String, Object>>of()
                : sourceStatuses) {
            String source = CatalogMapSupport.str(status.get(CatalogKeys.SOURCE));
            if (source.isBlank()) {
                continue;
            }
            YieldKey key = new YieldKey(source, shape);
            RollingWindow window = windows.computeIfAbsent(key, ignored -> {
                evictOldestIfNeeded();
                return new RollingWindow();
            });
            window.add(Observation.from(status));
            snapshots.put(source, window.snapshot());
        }
        return snapshots;
    }

    private void evictOldestIfNeeded() {
        if (windows.size() < MAX_SERIES) {
            return;
        }
        windows.entrySet().stream()
                .min(Comparator.comparingLong(entry -> entry.getValue().lastUpdatedNanos()))
                .map(Map.Entry::getKey)
                .ifPresent(windows::remove);
    }

    private static String normalizeShape(String queryShape) {
        String value = queryShape == null ? "" : queryShape.trim().toLowerCase();
        return value.isBlank() ? "unspecified" : value;
    }

    private record YieldKey(String source, String queryShape) {}

    private record Observation(
            long durationMs,
            int candidateCount,
            int verifiedYield,
            boolean empty,
            boolean timeout,
            boolean budgetExhausted,
            boolean error,
            boolean localIndexAvailable,
            boolean mirrorAvailable) {

        static Observation from(Map<String, Object> status) {
            String terminal = CatalogMapSupport.str(status.get("terminal_status"));
            return new Observation(
                    number(status.get("active_work_ms"), number(status.get("duration_ms"), 0L)),
                    CatalogMapSupport.toInt(status.get("candidate_count"), 0),
                    CatalogMapSupport.toInt(status.get("verified_yield"), 0),
                    bool(status.get("empty_result")),
                    bool(status.get("timeout")) || "timeout".equals(terminal),
                    bool(status.get("budget_exhausted")) || "budget_exhausted".equals(terminal),
                    "error".equals(terminal) || !CatalogMapSupport.str(status.get("error_category")).isBlank(),
                    number(status.get("local_index_call_count"), 0L) > 0,
                    CatalogMapSupport.toInt(status.get("sidecar_seed_count"), 0) > 0);
        }
    }

    private static final class RollingWindow {
        private final Deque<Observation> observations = new ArrayDeque<>(WINDOW_SIZE);
        private long lastUpdatedNanos = System.nanoTime();

        synchronized void add(Observation observation) {
            if (observations.size() == WINDOW_SIZE) {
                observations.removeFirst();
            }
            observations.addLast(observation);
            lastUpdatedNanos = System.nanoTime();
        }

        synchronized Map<String, Object> snapshot() {
            List<Observation> values = List.copyOf(observations);
            List<Long> durations = values.stream().map(Observation::durationMs).sorted().toList();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sample_count", values.size());
            result.put("latency_p50_ms", percentile(durations, 0.50));
            result.put("latency_p95_ms", percentile(durations, 0.95));
            result.put("empty_rate", rate(values, Observation::empty));
            result.put("timeout_rate", rate(values, Observation::timeout));
            result.put("budget_exhausted_rate", rate(values, Observation::budgetExhausted));
            result.put("error_rate", rate(values, Observation::error));
            result.put("mean_candidate_yield", mean(values.stream().map(Observation::candidateCount).toList()));
            result.put("mean_verified_yield", mean(values.stream().map(Observation::verifiedYield).toList()));
            result.put("local_index_available_rate", rate(values, Observation::localIndexAvailable));
            result.put("mirror_available_rate", rate(values, Observation::mirrorAvailable));
            result.put("decision_mode", "observe_only");
            return result;
        }

        synchronized long lastUpdatedNanos() {
            return lastUpdatedNanos;
        }
    }

    private static long percentile(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) {
            return 0L;
        }
        int index = Math.min(sorted.size() - 1, Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1));
        return sorted.get(index);
    }

    private static double rate(List<Observation> values, java.util.function.Predicate<Observation> predicate) {
        if (values.isEmpty()) {
            return 0.0;
        }
        return values.stream().filter(predicate).count() / (double) values.size();
    }

    private static double mean(List<Integer> values) {
        return values.isEmpty() ? 0.0 : values.stream().mapToInt(Integer::intValue).average().orElse(0.0);
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean flag ? flag : Boolean.parseBoolean(String.valueOf(value));
    }

    private static long number(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
