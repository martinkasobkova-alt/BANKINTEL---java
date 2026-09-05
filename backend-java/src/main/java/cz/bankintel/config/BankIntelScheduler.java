package cz.bankintel.config;

import cz.bankintel.connector.health.ConnectorHealthService;
import cz.bankintel.explore.EuMembership;
import cz.bankintel.explore.manager.fetch.ManagerMirrorCacheRefreshService;
import cz.bankintel.explore.manager.refresh.ManagerEurostatCacheRefreshService;
import cz.bankintel.service.content.RssFeedSyncService;
import cz.bankintel.service.platform.BankIntelMaintenanceService;
import cz.bankintel.repository.RssFeedRepository;
import cz.bankintel.domain.entity.RssFeedEntity;
import cz.bankintel.search.CatalogWarmupService;
import cz.bankintel.sources.catalog.MacroTopicsSnapshotService;
import cz.bankintel.util.BankIntelEnvVars;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic background jobs — port {@code Bankoapp-main/backend/services/scheduler.py}.
 *
 * <p>Catalog nightly warmup (03:00 UTC), manager mirror cache refresh (6h), macro snapshot placeholder
 * (00:00 Europe/Prague — {@code fixedDelay} until timezone cron is wired).
 *
 * <p>All jobs except {@link #rssDueSync()} require {@code BANKINTEL_SCHEDULER_LEADER=1} on exactly one
 * replica in multi-instance production.
 */
@Component
public class BankIntelScheduler {

    private static final Logger log = LoggerFactory.getLogger(BankIntelScheduler.class);

    private final CatalogWarmupService catalogWarmupService;
    private final ManagerMirrorCacheRefreshService managerMirrorCacheRefreshService;
    private final MacroTopicsSnapshotService macroTopicsSnapshotService;
    private final RssFeedSyncService rssFeedSyncService;
    private final RssFeedRepository rssFeedRepository;
    private final BankIntelMaintenanceService maintenanceService;
    private final ManagerEurostatCacheRefreshService managerEurostatCacheRefreshService;
    private final ConnectorHealthService connectorHealthService;

    public BankIntelScheduler(
            CatalogWarmupService catalogWarmupService,
            ManagerMirrorCacheRefreshService managerMirrorCacheRefreshService,
            MacroTopicsSnapshotService macroTopicsSnapshotService,
            RssFeedSyncService rssFeedSyncService,
            RssFeedRepository rssFeedRepository,
            BankIntelMaintenanceService maintenanceService,
            ManagerEurostatCacheRefreshService managerEurostatCacheRefreshService,
            ConnectorHealthService connectorHealthService) {
        this.catalogWarmupService = catalogWarmupService;
        this.managerMirrorCacheRefreshService = managerMirrorCacheRefreshService;
        this.macroTopicsSnapshotService = macroTopicsSnapshotService;
        this.rssFeedSyncService = rssFeedSyncService;
        this.rssFeedRepository = rssFeedRepository;
        this.maintenanceService = maintenanceService;
        this.managerEurostatCacheRefreshService = managerEurostatCacheRefreshService;
        this.connectorHealthService = connectorHealthService;
    }

    static boolean isSchedulerLeader() {
        return BankIntelEnvVars.isTruthy("BANKINTEL_SCHEDULER_LEADER");
    }

    private boolean skipIfNotLeader(String jobName) {
        if (isSchedulerLeader()) {
            return false;
        }
        log.info("scheduled {} skipped (BANKINTEL_SCHEDULER_LEADER is not set on this instance)", jobName);
        return true;
    }

    /** Ref scheduler.py {@code catalog_series_index:nightly} — CronTrigger(hour=3, minute=0, timezone=UTC). */
    @Scheduled(cron = "0 0 3 * * *")
    public void catalogWarmupNightly() {
        if (skipIfNotLeader("catalogWarmupNightly")) {
            return;
        }
        log.info("scheduled catalog warmup starting (03:00 UTC)");
        try {
            MapResult result = MapResult.from(catalogWarmupService.triggerWarmup());
            log.info("scheduled catalog warmup triggered status={}", result.get("status"));
        } catch (Exception ex) {
            log.warn("scheduled catalog warmup failed: {}", ex.getMessage());
        }
    }

    /** Ref scheduler.py rss_feeds:due_sync — every 5 minutes. */
    @Scheduled(fixedDelay = 5 * 60 * 1000L, initialDelay = 90_000L)
    public void rssDueSync() {
        try {
            int synced = 0;
            for (RssFeedEntity feed : rssFeedRepository.findAllEnabled()) {
                if (!rssFeedSyncService.isDueForSync(feed)) {
                    continue;
                }
                rssFeedSyncService.syncFeedSystem(feed);
                synced++;
            }
            if (synced > 0) {
                log.info("scheduled RSS sync completed feeds={}", synced);
            }
        } catch (Exception ex) {
            log.warn("scheduled RSS sync failed: {}", ex.getMessage());
        }
    }

    /**
     * Reachability probe of every external data source, hourly.
     *
     * <p>Intentionally not leader-gated: connectivity can differ per instance (egress, DNS, an
     * expired key on one replica) and {@code /api/health/connectors} reports the state of whichever
     * instance answers, so every replica keeps its own picture. Cost is ~11 HEAD-weight GETs/hour.
     */
    @Scheduled(fixedDelay = 60 * 60 * 1000L, initialDelay = 45_000L)
    public void connectorHealthProbe() {
        try {
            connectorHealthService.probeAll();
        } catch (Exception ex) {
            log.warn("scheduled connector health probe failed: {}", ex.getMessage());
        }
    }

    /** Optional shell-out to Python FTS build + mirror imports when BANKINTEL_MAINTENANCE_ENABLED=1. */
    @Scheduled(cron = "0 30 2 * * *")
    public void nightlyExternalMaintenance() {
        if (skipIfNotLeader("nightlyExternalMaintenance")) {
            return;
        }
        if (!maintenanceService.maintenanceEnabled()) {
            return;
        }
        log.info("scheduled external maintenance starting");
        try {
            var result = maintenanceService.runNightlyMaintenance();
            log.info("scheduled external maintenance finished ok={}", result.get("ok"));
        } catch (Exception ex) {
            log.warn("scheduled external maintenance failed: {}", ex.getMessage());
        }
    }

    /** Ref scheduled_cache_refresh.py — manager_series_cache refresh (partial: mirror CSV cache clear). */
    @Scheduled(fixedDelay = 6 * 60 * 60 * 1000L, initialDelay = 60_000L)
    public void managerCacheRefresh() {
        if (skipIfNotLeader("managerCacheRefresh")) {
            return;
        }
        log.info("scheduled manager cache refresh starting");
        try {
            managerMirrorCacheRefreshService.refreshMirrorCaches();
        } catch (Exception ex) {
            log.warn("scheduled manager cache refresh failed: {}", ex.getMessage());
        }
    }

    /**
     * Nightly native-Java Eurostat refresh of {@code manager_series_cache} (Phase 3 of the
     * Python-to-Java port — see {@link ManagerEurostatCacheRefreshService}). 04:00 UTC, offset
     * an hour from {@link #catalogWarmupNightly()} so the two don't compete for outbound HTTP/CPU
     * at the same instant. Gated on BOTH {@code skipIfNotLeader} (like every other multi-replica
     * job here) AND {@code MANAGER_SERIES_CACHE_JAVA_REFRESH_ENABLED} specifically — a separate
     * flag so this one job can be instantly disabled (rollback with no redeploy) without touching
     * the leader-election flag every other scheduled job also depends on. Live-verified manually
     * before this was wired in: 8,713 targets, ~80% loaded, written to the real collection in
     * ~4.5 minutes (2026-08-07). The legacy Python script keeps running for Eurostat too — no
     * change there — so both writers target the same collection using the same
     * {@code (series_id, geo)} upsert key (see {@link
     * cz.bankintel.explore.manager.refresh.ManagerSeriesCacheWriter}).
     */
    @Scheduled(cron = "0 0 4 * * *")
    public void managerEurostatCacheRefreshNightly() {
        if (skipIfNotLeader("managerEurostatCacheRefreshNightly")) {
            return;
        }
        if (!BankIntelEnvVars.isTruthy("MANAGER_SERIES_CACHE_JAVA_REFRESH_ENABLED")) {
            log.info("scheduled manager eurostat cache refresh skipped (MANAGER_SERIES_CACHE_JAVA_REFRESH_ENABLED not set)");
            return;
        }
        log.info("scheduled manager eurostat cache refresh starting (04:00 UTC)");
        try {
            var report = managerEurostatCacheRefreshService.refresh(
                    java.util.Set.of(), EuMembership.ISO2_CODES, false, "manager_series_cache");
            log.info("scheduled manager eurostat cache refresh finished: {}", report);
        } catch (Exception ex) {
            log.warn("scheduled manager eurostat cache refresh failed: {}", ex.getMessage());
        }
    }

    /**
     * Ref scheduler.py {@code macro_topics_snapshot:nightly} — CronTrigger(hour=0, minute=0,
     * timezone=Europe/Prague). Placeholder uses fixedDelay until {@code zone} cron is enabled.
     */
    @Scheduled(fixedDelay = 24 * 60 * 60 * 1000L, initialDelay = 120_000L)
    public void macroSnapshotPlaceholder() {
        if (skipIfNotLeader("macroSnapshotPlaceholder")) {
            return;
        }
        log.info("scheduled macro topics snapshot tick");
        try {
            macroTopicsSnapshotService.rebuildPlaceholder();
        } catch (Exception ex) {
            log.warn("macro snapshot rebuild failed: {}", ex.getMessage());
        }
    }

    /** Thin wrapper so scheduler does not depend on raw Map typing in logs. */
    private record MapResult(java.util.Map<String, Object> map) {
        static MapResult from(java.util.Map<String, Object> map) {
            return new MapResult(map != null ? map : java.util.Map.of());
        }

        Object get(String key) {
            return map.get(key);
        }
    }
}
