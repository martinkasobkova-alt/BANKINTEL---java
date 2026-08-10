package cz.bankintel.config;

import cz.bankintel.sources.eurostat.EurostatWarmupService;
import cz.bankintel.util.BankIntelEnvVars;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Triggers Eurostat dimension-resolution cache warmup on startup (kolo 6, #3b). */
@Component
@Order(60)
public class EurostatWarmupBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EurostatWarmupBootstrapRunner.class);

    private final EurostatWarmupService eurostatWarmupService;

    public EurostatWarmupBootstrapRunner(EurostatWarmupService eurostatWarmupService) {
        this.eurostatWarmupService = eurostatWarmupService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!warmupEnabled()) {
            log.info("eurostat dimension warmup on startup disabled (EUROSTAT_WARMUP_ON_STARTUP=0)");
            return;
        }
        log.info("eurostat dimension warmup on startup — triggering background warmup");
        eurostatWarmupService.triggerWarmup();
    }

    static boolean warmupEnabled() {
        String raw = BankIntelEnvVars.get("EUROSTAT_WARMUP_ON_STARTUP");
        return raw == null || raw.isBlank() || !"0".equals(raw.trim());
    }
}
