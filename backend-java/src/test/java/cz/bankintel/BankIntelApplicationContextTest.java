package cz.bankintel;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Smoke test — boots full Spring web/security context (catches filter-order regressions).
 *
 * <p>Uses the exact same {@code @SpringBootTest}/profile/property configuration as {@link
 * cz.bankintel.controller.HealthEndpointsIntegrationTest} so Spring's test context cache reuses a
 * single embedded-Postgres-backed context instead of paying for it twice.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestPropertySource(
        properties = {
            "CATALOG_SEARCH_WARMUP_ON_STARTUP=0",
            "DEV_SEED=false",
            "bankintel.dev.seed=false"
        })
class BankIntelApplicationContextTest {

    @Test
    void contextLoads() {}
}
