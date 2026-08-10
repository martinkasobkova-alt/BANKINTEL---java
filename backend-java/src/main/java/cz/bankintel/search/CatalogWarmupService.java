package cz.bankintel.search;
import cz.bankintel.util.BankIntelEnvVars;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Background warmup of catalog indexes — port {@code catalog_search_warmup.py}. */
@Service
public class CatalogWarmupService {

    private static final Logger log = LoggerFactory.getLogger(CatalogWarmupService.class);

    private static final List<Map.Entry<String, String>> WARMUP_SOURCES = List.of(
            Map.entry("ecb2", "bank profitability"),
            Map.entry("eurostat", "inflation HICP"),
            Map.entry("imf", "inflation consumer prices"),
            Map.entry("arad", "zisk bank"),
            Map.entry("data360", "gdp"),
            Map.entry("fred", "interest rate"),
            Map.entry("oecd4", "unemployment rate"),
            Map.entry("bis", "credit"));

    private final CatalogIndexStore catalogIndexStore;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Map<String, Object> lastStatus = defaultStatus();

    public CatalogWarmupService(CatalogIndexStore catalogIndexStore) {
        this.catalogIndexStore = catalogIndexStore;
    }

    public Map<String, Object> triggerWarmup() {
        Map<String, Object> status = statusSnapshot();
        if (!warmupEnabled()) {
            return Map.of("status", "disabled", "enabled", false);
        }
        if (running.compareAndSet(false, true)) {
            CompletableFuture.runAsync(this::runWarmup);
            status = statusSnapshot();
            return Map.of("status", "warming", "enabled", true, "running", true, "detail", status);
        }
        status = statusSnapshot();
        return Map.of("status", "warming", "enabled", true, "running", true, "detail", status);
    }

    public Map<String, Object> status() {
        Map<String, Object> status = statusSnapshot();
        String state = Boolean.TRUE.equals(status.get("running")) ? "running" : "idle";
        return Map.of("status", state, "enabled", warmupEnabled(), "detail", status);
    }

    private void runWarmup() {
        long started = System.currentTimeMillis();
        List<String> warmed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        try {
            for (Map.Entry<String, String> entry : WARMUP_SOURCES) {
                try {
                    catalogIndexStore.searchSource(entry.getKey(), entry.getValue(), 5);
                    warmed.add(entry.getKey());
                } catch (Exception ex) {
                    failed.add(entry.getKey());
                    log.debug("catalog warmup failed source={}: {}", entry.getKey(), ex.getMessage());
                }
            }
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("running", false);
            status.put("last_started_at", started);
            status.put("last_finished_at", System.currentTimeMillis());
            status.put("duration_ms", System.currentTimeMillis() - started);
            status.put("warmed_sources", warmed);
            status.put("failed_sources", failed);
            status.put("last_error", failed.isEmpty() ? "" : String.join(",", failed));
            status.put("enabled", warmupEnabled());
            lastStatus = status;
        } finally {
            running.set(false);
        }
    }

    private Map<String, Object> statusSnapshot() {
        Map<String, Object> copy = new LinkedHashMap<>(lastStatus);
        copy.put("running", running.get());
        copy.put("enabled", warmupEnabled());
        return copy;
    }

    private static boolean warmupEnabled() {
        String raw = BankIntelEnvVars.get("CATALOG_SEARCH_WARMUP_ON_STARTUP");
        return raw == null || raw.isBlank() || !"0".equals(raw.trim());
    }

    private static Map<String, Object> defaultStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("running", false);
        status.put("last_started_at", null);
        status.put("last_finished_at", null);
        status.put("duration_ms", 0);
        status.put("warmed_sources", List.of());
        status.put("failed_sources", List.of());
        status.put("last_error", null);
        status.put("enabled", warmupEnabled());
        return status;
    }
}
