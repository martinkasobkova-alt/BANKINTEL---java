package cz.bankintel.explore.manager.refresh;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoCollection;
import cz.bankintel.connector.ConnectorHttpSupport;
import cz.bankintel.connector.EurostatConnector;
import cz.bankintel.explore.EuMembership;
import cz.bankintel.explore.manager.ManagerSeriesCacheMongoConnection;
import cz.bankintel.explore.manager.refresh.ManagerEurostatCacheRefreshService.RefreshReport;
import cz.bankintel.sources.eurostat.EurostatDimensionService;
import cz.bankintel.sources.eurostat.EurostatRateLimiter;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Phase 1 correctness check against the REAL curated bundle files and the REAL Eurostat API -
 * dry-run only (never touches Mongo, matching this codebase's {@code *ManualTest} convention for
 * tests that genuinely need live network - see {@code EurostatDimensionServiceManualTest}). Not
 * run as part of routine development; used once to confirm the refresh pipeline end to end
 * before any Mongo write is attempted.
 */
class ManagerEurostatCacheRefreshServiceManualTest {

    @Test
    void refreshManufacturingGeneralForItDeCz_liveNetworkDryRun() {
        ObjectMapper objectMapper = new ObjectMapper();
        ManagerSegmentBundleLoader bundleLoader = new ManagerSegmentBundleLoader(objectMapper);
        // Curated bundle rows already carry complete query_params (verified: 0/432 need this
        // fallback today) - a mock that would throw on any real call proves that guarantee holds
        // for this specific Phase 1 scope, without needing a working EurostatDimensionService.
        EurostatDimensionService dimensionService = mock(EurostatDimensionService.class);
        ManagerEurostatRefreshTargetBuilder targetBuilder = new ManagerEurostatRefreshTargetBuilder(dimensionService);
        ManagerSeriesCacheWriter writer = mock(ManagerSeriesCacheWriter.class); // dry-run: never invoked.
        EurostatConnector connector = new EurostatConnector(new ConnectorHttpSupport(objectMapper));
        EurostatRateLimiter rateLimiter = new EurostatRateLimiter();

        ManagerEurostatCacheRefreshService service =
                new ManagerEurostatCacheRefreshService(bundleLoader, targetBuilder, writer, connector, rateLimiter);

        RefreshReport report = service.refresh(
                Set.of("manufacturing_general"), Set.of("IT", "DE", "CZ"), true, "manager_series_cache_java_test");

        assertTrue(report.targets() > 0, "expected at least one (series, geo) target for manufacturing_general/IT-DE-CZ");
        assertFalse(report.loaded() == 0, "expected at least some series to load real observations: " + report);
        org.mockito.Mockito.verifyNoInteractions(writer);
        System.out.println("Phase 1 dry-run report: " + report);
    }

    /**
     * Phase 1, step 2: a REAL Mongo write - but only ever to the shadow collection {@code
     * manager_series_cache_java_test}, never the real {@code manager_series_cache} the live app
     * reads from. Requires {@code MONGO_URL} to be set in the environment for this JVM (not
     * committed anywhere by this test); skips itself via {@link Assumptions} if it isn't, rather
     * than failing a routine test run for everyone else.
     */
    @Test
    void refreshManufacturingGeneralForItDeCz_liveNetworkLiveWriteToShadowCollection() {
        ManagerSeriesCacheMongoConnection mongoConnection = new ManagerSeriesCacheMongoConnection();
        Assumptions.assumeTrue(mongoConnection.isAvailable(), "MONGO_URL not set - skipping live Mongo write check");

        ObjectMapper objectMapper = new ObjectMapper();
        ManagerSegmentBundleLoader bundleLoader = new ManagerSegmentBundleLoader(objectMapper);
        EurostatDimensionService dimensionService = mock(EurostatDimensionService.class);
        ManagerEurostatRefreshTargetBuilder targetBuilder = new ManagerEurostatRefreshTargetBuilder(dimensionService);
        ManagerSeriesCacheWriter writer = new ManagerSeriesCacheWriter(mongoConnection);
        EurostatConnector connector = new EurostatConnector(new ConnectorHttpSupport(objectMapper));
        EurostatRateLimiter rateLimiter = new EurostatRateLimiter();

        ManagerEurostatCacheRefreshService service =
                new ManagerEurostatCacheRefreshService(bundleLoader, targetBuilder, writer, connector, rateLimiter);

        String shadowCollection = "manager_series_cache_java_test";
        writer.ensureIndexes(shadowCollection);
        RefreshReport report =
                service.refresh(Set.of("manufacturing_general"), Set.of("IT", "DE", "CZ"), false, shadowCollection);

        assertTrue(report.written() > 0, "expected at least one document actually written: " + report);
        System.out.println("Phase 1 live-write report: " + report);

        MongoCollection<Document> written = mongoConnection.collection(shadowCollection);
        Document sample = written.find(com.mongodb.client.model.Filters.eq("geo", "IT")).first();
        assertTrue(sample != null, "expected at least one written IT document in " + shadowCollection);
        System.out.println("Sample written doc: " + sample.toJson());
    }

    /**
     * Phase 2: full Eurostat coverage (all 25 segment bundles x EU27) against the REAL {@code
     * manager_series_cache} collection the live app reads from - not a shadow collection. Gated
     * on an explicit extra confirmation flag on top of {@code MONGO_URL} (not just "Mongo is
     * reachable") specifically so this can never fire as a side effect of a routine "run all
     * manual tests" sweep - it must be deliberately requested every time.
     */
    @Test
    void refreshAllSegmentsForEu27_liveNetworkLiveWriteToRealCollection() {
        ManagerSeriesCacheMongoConnection mongoConnection = new ManagerSeriesCacheMongoConnection();
        Assumptions.assumeTrue(mongoConnection.isAvailable(), "MONGO_URL not set - skipping Phase 2 run");
        boolean confirmed = "1".equals(System.getProperty("MANAGER_EUROSTAT_PHASE2_CONFIRM"))
                || "1".equals(System.getenv("MANAGER_EUROSTAT_PHASE2_CONFIRM"));
        Assumptions.assumeTrue(confirmed, "MANAGER_EUROSTAT_PHASE2_CONFIRM=1 not set - skipping Phase 2 full production run");

        ObjectMapper objectMapper = new ObjectMapper();
        ManagerSegmentBundleLoader bundleLoader = new ManagerSegmentBundleLoader(objectMapper);
        EurostatDimensionService dimensionService = mock(EurostatDimensionService.class);
        ManagerEurostatRefreshTargetBuilder targetBuilder = new ManagerEurostatRefreshTargetBuilder(dimensionService);
        ManagerSeriesCacheWriter writer = new ManagerSeriesCacheWriter(mongoConnection);
        EurostatConnector connector = new EurostatConnector(new ConnectorHttpSupport(objectMapper));
        EurostatRateLimiter rateLimiter = new EurostatRateLimiter();

        ManagerEurostatCacheRefreshService service =
                new ManagerEurostatCacheRefreshService(bundleLoader, targetBuilder, writer, connector, rateLimiter);

        String realCollection = "manager_series_cache";
        writer.ensureIndexes(realCollection);
        long startedAtMs = System.currentTimeMillis();
        RefreshReport report = service.refresh(Set.of(), EuMembership.ISO2_CODES, false, realCollection);
        long elapsedSec = (System.currentTimeMillis() - startedAtMs) / 1000;

        System.out.println("Phase 2 full production run report: " + report + " elapsed_sec=" + elapsedSec);
        assertTrue(report.targets() > 1000, "expected several thousand targets for a full EU27 x 25-segment run: " + report);
    }
}
