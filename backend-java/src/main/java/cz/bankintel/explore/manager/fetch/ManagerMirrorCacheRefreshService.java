package cz.bankintel.explore.manager.fetch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Clears in-process manager mirror CSV caches — partial port of
 * {@code backend/scripts/scheduled_cache_refresh.py} / nightly manager_series_cache refresh.
 */
@Service
public class ManagerMirrorCacheRefreshService {

    private static final Logger log = LoggerFactory.getLogger(ManagerMirrorCacheRefreshService.class);

    private final ManagerMirrorFetchSupport mirrorFetchSupport;

    public ManagerMirrorCacheRefreshService(ManagerMirrorFetchSupport mirrorFetchSupport) {
        this.mirrorFetchSupport = mirrorFetchSupport;
    }

    public void refreshMirrorCaches() {
        mirrorFetchSupport.clearAllCaches();
        log.info("manager mirror CSV caches cleared (full Mongo refresh remains external — see scheduled_cache_refresh.py)");
    }
}
