package cz.bankintel.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tripwire for two exposures that anyone able to reach the server could use.
 *
 * <p>Authorization here is enforced in controllers <em>or</em> in services, and
 * {@code SecurityConfig} ends in {@code anyRequest().permitAll()} — so a forgotten check fails
 * silently rather than loudly. These assertions make the build notice instead of production.
 */
class AiEndpointExposureTest {

    private static final Path CATALOG_CONTROLLER =
            Path.of("src/main/java/cz/bankintel/controller/catalog/CatalogController.java");

    /**
     * OpenAI-backed endpoints anonymous callers can reach. Uncapped, each is a way for a stranger
     * to spend the AI budget — explain/related call OpenAI directly, routing and intent reach it
     * through the query planner.
     */
    private static final List<String> AI_ENDPOINTS_REACHABLE_ANONYMOUSLY = List.of(
            "/api/catalog/deep-search",
            "/api/catalog/search-v2",
            "/api/catalog/deep-search/followup",
            "/api/catalog/deep-search/results-chat",
            "/api/catalog/explain-series",
            "/api/catalog/explain-series/ask",
            "/api/catalog/related-series",
            "/api/catalog/source-route",
            "/api/catalog/deep-search/source-route",
            "/api/catalog/deep-search/results-intent",
            "/api/explore/sector",
            "/api/explore/sector/refine",
            "/api/explore/summarize",
            "/api/explore/summarize/followup",
            "/api/explore/query-understanding",
            "/api/explore/related-suggestions",
            "/api/explore/country-suggestions",
            "/api/explore/manager/analysis-plan",
            "/api/magazines/ai/chat",
            "/api/magazines/ai/search",
            "/api/chart-agent/ask",
            "/api/chart-agent/intent");

    /**
     * Creating a catalog source writes into the shared {@code sources} table. All three source
     * families must agree that this is an admin operation — Eurostat was the odd one out.
     */
    private static final List<String> ADMIN_ONLY_ADD_SOURCE = List.of(
            "src/main/java/cz/bankintel/controller/sources/AradCatalogController.java",
            "src/main/java/cz/bankintel/controller/sources/EurostatCatalogController.java");

    /** Full index rebuilds — the most expensive jobs the backend can run, and no UI calls them. */
    private static final List<String> ADMIN_ONLY_INDEX_HANDLERS =
            List.of("searchV2SidecarRebuild", "searchV2SidecarOptimize", "searchV2VectorRebuild");

    @Test
    void everyAnonymouslyReachableAiEndpointIsRateLimited() {
        for (String path : AI_ENDPOINTS_REACHABLE_ANONYMOUSLY) {
            assertThat(AuthRateLimitFilter.limitForPath(path))
                    .as("no rate limit for AI endpoint %s — anonymous callers could spend the AI budget", path)
                    .isNotNull()
                    .isPositive();
        }
    }

    @Test
    void indexRebuildHandlersKeepTheirAdminGuard() throws Exception {
        // Source inspection rather than invocation: these handlers need a full Spring context and an
        // index on disk, while the only question worth asking is whether the guard is still there.
        String source = Files.readString(CATALOG_CONTROLLER, StandardCharsets.UTF_8);

        for (String handler : ADMIN_ONLY_INDEX_HANDLERS) {
            int start = source.indexOf(handler + "(");
            assertThat(start).as("handler %s no longer exists — did the endpoint move?", handler).isPositive();
            int end = source.indexOf("\n    }", start);
            assertThat(end).as("could not delimit the body of %s", handler).isPositive();

            assertThat(source.substring(start, end))
                    .as("%s lost its adminAccess.requireAdmin() guard; neither this controller nor the "
                            + "index service checked anything before, so it was fully open", handler)
                    .contains("adminAccess.requireAdmin()");
        }
    }

    @Test
    void addingACatalogSourceRequiresAdminInEverySourceFamily() throws Exception {
        for (String controller : ADMIN_ONLY_ADD_SOURCE) {
            String source = Files.readString(Path.of(controller), StandardCharsets.UTF_8);
            int start = source.indexOf("addSource(");
            assertThat(start).as("%s has no addSource handler", controller).isPositive();
            int end = source.indexOf("\n    }", start);

            assertThat(source.substring(start, end))
                    .as("%s: add-source writes into the shared sources table and must require admin", controller)
                    .contains("adminAccess.requireAdmin()");
        }
    }

    @Test
    void authFlowsThatMustStayPublicAreStillThrottled() {
        // Login and registration cannot require auth, so the rate limit is the only brake there.
        assertThat(AuthRateLimitFilter.limitForPath("/api/auth/login")).isNotNull().isPositive();
        assertThat(AuthRateLimitFilter.limitForPath("/api/auth/register")).isNotNull().isPositive();
        assertThat(AuthRateLimitFilter.limitForPath("/api/auth/reset-password")).isNotNull().isPositive();
    }

    @Test
    void ordinaryCatalogBrowsingStaysUnthrottled() {
        // Public catalog reads are the product's shop window and cost nothing in AI tokens —
        // throttling them would be a regression, not a fix.
        assertThat(AuthRateLimitFilter.limitForPath("/api/catalog/preview")).isNull();
        assertThat(AuthRateLimitFilter.limitForPath("/api/catalog/search")).isNull();
    }
}
