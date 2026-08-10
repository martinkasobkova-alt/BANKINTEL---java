package cz.bankintel.config;

import cz.bankintel.search.CatalogWarmupService;
import cz.bankintel.util.BankIntelEnvVars;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Triggers catalog index warmup on startup — port {@code catalog_search_warmup.py}. */
@Component
@Order(50)
public class CatalogWarmupBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CatalogWarmupBootstrapRunner.class);

    private final CatalogWarmupService catalogWarmupService;

    public CatalogWarmupBootstrapRunner(CatalogWarmupService catalogWarmupService) {
        this.catalogWarmupService = catalogWarmupService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!warmupEnabled()) {
            log.info("catalog FTS warmup on startup disabled (CATALOG_SEARCH_WARMUP_ON_STARTUP=0)");
            return;
        }
        log.info("catalog FTS warmup on startup — triggering background warmup");
        catalogWarmupService.triggerWarmup();
    }

    static boolean warmupEnabled() {
        String raw = BankIntelEnvVars.get("CATALOG_SEARCH_WARMUP_ON_STARTUP");
        return raw == null || raw.isBlank() || !"0".equals(raw.trim());
    }
}
