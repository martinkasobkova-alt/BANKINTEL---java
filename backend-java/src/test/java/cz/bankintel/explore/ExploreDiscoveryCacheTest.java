package cz.bankintel.explore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.v2.entity.ExactEntityResolver;
import cz.bankintel.search.v2.entity.SearchV2SourceCapabilityRegistry;
import cz.bankintel.search.v2.ontology.SearchV2ConceptRegistry;
import cz.bankintel.search.v2.ontology.SearchV2InstitutionalSectorRegistry;
import cz.bankintel.search.v2.ontology.SearchV2MetricIntentRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ETAPA 6: {@link ExploreDiscoveryCache#buildKey} must be a pure function of deterministic
 * signals only - the raw query text plus the existing metric-intent/institutional-sector/concept/
 * exact-entity/geo registries the Search V2 planner already uses - never of the LLM planner's own
 * free-form search-term variants (which MANAGER_EXPLORER_AUDIT.md section P2 showed can differ
 * between two calls for the identical query: 6 back-to-back identical requests returned {@code
 * verified} counts of 6, 5, 4, 3, 3, 5). These tests use REAL registry instances (JSON-backed, no
 * LLM, no mocking of the thing being tested) so the determinism claim is genuine, not assumed.
 */
class ExploreDiscoveryCacheTest {

    private static ExploreDiscoveryCache newCache() {
        ObjectMapper mapper = new ObjectMapper();
        return new ExploreDiscoveryCache(
                new SearchV2MetricIntentRegistry(mapper),
                new SearchV2InstitutionalSectorRegistry(mapper),
                new SearchV2ConceptRegistry(mapper),
                new ExactEntityResolver(mapper, new SearchV2SourceCapabilityRegistry(mapper)));
    }

    @Test
    void sameQueryAndConfigAlwaysBuildsTheSameKey() {
        ExploreDiscoveryCache cache = newCache();
        String key1 = cache.buildKey("ziskovost ceskych bank a uverovani", "banking_finance", false);
        String key2 = cache.buildKey("ziskovost ceskych bank a uverovani", "banking_finance", false);
        String key3 = cache.buildKey("ziskovost ceskych bank a uverovani", "banking_finance", false);
        assertEquals(key1, key2);
        assertEquals(key2, key3);
    }

    @Test
    void differentBroaderSearchBuildsADifferentKey() {
        ExploreDiscoveryCache cache = newCache();
        String base = cache.buildKey("inflace v Cesku", "macro_economy", false);
        String differentBroader = cache.buildKey("inflace v Cesku", "macro_economy", true);
        assertNotEquals(base, differentBroader);
    }

    @Test
    void sectorOnlyModeWithNoQuestionUsesSectorAsTheQueryItself() {
        ExploreDiscoveryCache cache = newCache();
        String a = cache.buildKey("", "macro_economy", false);
        String b = cache.buildKey("", "other_sector", false);
        assertNotEquals(a, b);
    }

    // ETAPA 6 regression: live-verified with "ziskovost pojistovnictvi ve Spanelsku" - when a
    // question drives the request, ExploreSectorService#prepareAnalysis derives `sector` from a
    // SEPARATE, uncached AI query-understanding call whenever the free-text classification
    // doesn't land on one of the 20 fixed presets. That classification can itself vary between
    // otherwise-identical calls. Folding it into the key produced a 0% cache hit rate for this
    // exact query in the live 10x stability run (every call recomputed a fresh result with a
    // different candidate set and verified count). The key must therefore be stable across
    // different sector CLASSIFICATIONS of the identical question.
    @Test
    void sameQuestionWithDifferentAiClassifiedSectorStillBuildsTheSameKey() {
        ExploreDiscoveryCache cache = newCache();
        String withOneClassification = cache.buildKey("ziskovost pojistovnictvi ve Spanelsku", "Pojišťovnictví", false);
        String withAnotherClassification =
                cache.buildKey("ziskovost pojistovnictvi ve Spanelsku", "Pojišťovnictví a penze", false);
        String withNoClassification = cache.buildKey("ziskovost pojistovnictvi ve Spanelsku", "", false);
        assertEquals(withOneClassification, withAnotherClassification);
        assertEquals(withOneClassification, withNoClassification);
    }

    @Test
    void differentQueryBuildsADifferentKey() {
        ExploreDiscoveryCache cache = newCache();
        String a = cache.buildKey("zadluzeni domacnosti", "macro_economy", false);
        String b = cache.buildKey("HDP Nemecko", "macro_economy", false);
        assertNotEquals(a, b);
    }

    @Test
    void putThenGetReturnsTheStoredEntryUntilExpired() {
        ExploreDiscoveryCache cache = newCache();
        String key = cache.buildKey("ziskovost pojistovnictvi ve Spanelsku", "insurance", false);
        List<Map<String, Object>> sector = List.of(Map.of("source", "data360", "set_id", "ROE_ES_INS"));
        List<Map<String, Object>> macro = List.of();

        assertMiss(cache, key);
        cache.put(key, sector, macro, 1);

        var hit = cache.get(key);
        assertTrue(hit.isPresent());
        assertEquals(sector, hit.get().sectorIndicators());
        assertEquals(1, hit.get().totalCandidates());
    }

    @Test
    void cachePreservesOriginalComputeTimeSeparatelyFromServingTime() {
        ExploreDiscoveryCache cache = newCache();
        String key = cache.buildKey("bank profitability", "banking_finance", false);
        long computeTimeMs = 37L;

        cache.put(key, List.of(), List.of(), 0, computeTimeMs);

        var hit = cache.get(key).orElseThrow();
        assertEquals(computeTimeMs, hit.computeTimeMs());
    }

    private static void assertMiss(ExploreDiscoveryCache cache, String key) {
        assertTrue(cache.get(key).isEmpty());
    }
}
