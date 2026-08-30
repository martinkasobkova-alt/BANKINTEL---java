package cz.bankintel.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import cz.bankintel.connector.health.ConnectorHealthService;
import cz.bankintel.explore.manager.fetch.ManagerMirrorCacheRefreshService;
import cz.bankintel.explore.manager.refresh.ManagerEurostatCacheRefreshService;
import cz.bankintel.repository.RssFeedRepository;
import cz.bankintel.search.CatalogWarmupService;
import cz.bankintel.service.content.RssFeedSyncService;
import cz.bankintel.service.platform.BankIntelMaintenanceService;
import cz.bankintel.sources.catalog.MacroTopicsSnapshotService;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The nightly Eurostat refresh job must be gated by BOTH {@code BANKINTEL_SCHEDULER_LEADER} (like
 * every other multi-replica job) AND {@code MANAGER_SERIES_CACHE_JAVA_REFRESH_ENABLED}
 * independently - either one being unset must skip the run without touching the refresh service.
 */
class BankIntelSchedulerManagerEurostatRefreshGateTest {

    @AfterEach
    void cleanup() {
        System.clearProperty("BANKINTEL_SCHEDULER_LEADER");
        System.clearProperty("MANAGER_SERIES_CACHE_JAVA_REFRESH_ENABLED");
    }

    private static BankIntelScheduler newScheduler(ManagerEurostatCacheRefreshService refreshService) {
        return new BankIntelScheduler(
                mock(CatalogWarmupService.class),
                mock(ManagerMirrorCacheRefreshService.class),
                mock(MacroTopicsSnapshotService.class),
                mock(RssFeedSyncService.class),
                mock(RssFeedRepository.class),
                mock(BankIntelMaintenanceService.class),
                refreshService,
                mock(ConnectorHealthService.class));
    }

    @Test
    void skipsWhenNotSchedulerLeaderEvenIfFlagEnabled() {
        System.setProperty("MANAGER_SERIES_CACHE_JAVA_REFRESH_ENABLED", "1");
        ManagerEurostatCacheRefreshService refreshService = mock(ManagerEurostatCacheRefreshService.class);
        BankIntelScheduler scheduler = newScheduler(refreshService);

        scheduler.managerEurostatCacheRefreshNightly();

        verifyNoInteractions(refreshService);
    }

    @Test
    void skipsWhenLeaderButFlagNotEnabled() {
        System.setProperty("BANKINTEL_SCHEDULER_LEADER", "1");
        ManagerEurostatCacheRefreshService refreshService = mock(ManagerEurostatCacheRefreshService.class);
        BankIntelScheduler scheduler = newScheduler(refreshService);

        scheduler.managerEurostatCacheRefreshNightly();

        verifyNoInteractions(refreshService);
    }

    @Test
    void runsWhenBothLeaderAndFlagAreEnabled() {
        System.setProperty("BANKINTEL_SCHEDULER_LEADER", "1");
        System.setProperty("MANAGER_SERIES_CACHE_JAVA_REFRESH_ENABLED", "1");
        ManagerEurostatCacheRefreshService refreshService = mock(ManagerEurostatCacheRefreshService.class);
        BankIntelScheduler scheduler = newScheduler(refreshService);

        scheduler.managerEurostatCacheRefreshNightly();

        verify(refreshService).refresh(Set.of(), cz.bankintel.explore.EuMembership.ISO2_CODES, false, "manager_series_cache");
    }
}
