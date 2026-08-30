package cz.bankintel.explore;

import cz.bankintel.search.CatalogTextUtils;
import cz.bankintel.search.model.GeoIntentSnapshot;
import cz.bankintel.search.v2.entity.ExactEntityResolver;
import cz.bankintel.search.v2.ontology.SearchV2ConceptRegistry;
import cz.bankintel.search.v2.ontology.SearchV2InstitutionalSectorRegistry;
import cz.bankintel.search.v2.ontology.SearchV2MetricIntentRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Whole-discovery-result cache for {@link ExploreDiscoveryService}, keyed on a STABLE identity
 * built only from deterministic signals - the raw query, sector/broaderSearch config, and the
 * same deterministic registries the Search V2 planner already uses (metric intent, institutional
 * sector, concept registry, exact entity resolver, geo detection) - never from the LLM planner's
 * own free-form search-term variants, which can differ between two calls for the identical query.
 *
 * <p>Confirmed live (docs/archive/MANAGER_EXPLORER_AUDIT.md, section P2): 6 back-to-back identical requests for
 * the same query returned {@code verified} counts of 6, 5, 4, 3, 3, 5 - the LLM planner's variant
 * search terms (and therefore the FTS candidate pool and preview verification outcome) are not
 * guaranteed identical run to run, even though the deterministic signals ARE. Caching on the
 * deterministic identity means the SAME query serves the SAME baseline result for repeat callers
 * within the TTL, while the LLM planner still runs normally - and its variants still additively
 * supplement the deterministic base terms (see CatalogQueryPlanner.mergeSearchTerms) - on the
 * first (cache-miss) call for a given identity.
 */
@Service
@RequiredArgsConstructor
public class ExploreDiscoveryCache {

    private static final int MAX_ENTRIES = 256;
    private static final long TTL_MS = 20 * 60 * 1000L;

    private final SearchV2MetricIntentRegistry metricIntentRegistry;
    private final SearchV2InstitutionalSectorRegistry institutionalSectorRegistry;
    private final SearchV2ConceptRegistry conceptRegistry;
    private final ExactEntityResolver exactEntityResolver;

    private final Map<String, CachedEntry> cache =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedEntry> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    /**
     * Builds the stable cache key for a discovery request. Two calls with the same raw query and
     * broaderSearch flag always produce the same key, regardless of what the LLM planner's
     * search-term variants happened to be on either call.
     *
     * <p>The {@code sector} parameter is deliberately NOT folded into the key whenever a
     * {@code question} is present. {@code sector} at this call site (see {@code
     * ExploreSectorService#prepareAnalysis}) is itself the output of a SEPARATE, uncached
     * query-understanding LLM call ({@code ExploreQueryUnderstandingService}) whenever the raw
     * free-text classification doesn't land on one of the 20 fixed manager presets - i.e. it can
     * be just as volatile as the planner's own search terms. Live-verified (ETAPA 6 10x stability
     * run): for "ziskovost pojistovnictvi ve Spanelsku" the classified sector label differed often
     * enough between otherwise-identical calls that including it in the key produced a 0% cache
     * hit rate - every single call recomputed a fresh (and differently-timed, differently-ranked)
     * result. The question text's OWN deterministic institutional-sector fingerprint (below)
     * already captures the sector signal without that volatility. When {@code question} is
     * blank, {@code sector} is effectively a menu-driven selection (there is no free text to
     * classify), so it is used as the query text itself and needs no separate handling.
     */
    public String buildKey(String question, String sector, boolean broaderSearch) {
        boolean hasQuestion = question != null && !question.isBlank();
        String query = hasQuestion ? question.trim() : (sector == null ? "" : sector.trim());
        String folded = CatalogTextUtils.foldAscii(query).toLowerCase(Locale.ROOT);
        String metricIntent = safe(() -> metricIntentRegistry.resolve(query));
        String institutionalSector = safe(() -> institutionalSectorRegistry.resolve(query));
        String conceptFingerprint = safe(() -> String.join(",", conceptRegistry.resolve(query).conceptIds()));
        String entityFingerprint = safe(() -> {
            var entity = exactEntityResolver.resolve(query).entityResolution();
            return entity == null ? "" : entity.resolutionType() + ":" + nullToEmpty(entity.canonicalName());
        });
        String geoFingerprint = safe(() -> {
            GeoIntentSnapshot geo = GeoIntentSnapshot.fromDetection(query);
            return nullToEmpty(geo.type()) + ":" + String.join(",", geo.countryCodes());
        });
        return String.join(
                "|",
                folded,
                hasQuestion ? "" : nullToEmpty(sector).trim().toLowerCase(Locale.ROOT),
                String.valueOf(broaderSearch),
                "metric=" + metricIntent,
                "sector=" + institutionalSector,
                "concept=" + conceptFingerprint,
                "entity=" + entityFingerprint,
                "geo=" + geoFingerprint);
    }

    public Optional<CachedEntry> get(String key) {
        synchronized (cache) {
            CachedEntry entry = cache.get(key);
            if (entry == null) {
                return Optional.empty();
            }
            if (System.currentTimeMillis() - entry.computedAtMs() > TTL_MS) {
                cache.remove(key);
                return Optional.empty();
            }
            return Optional.of(entry);
        }
    }

    public void put(
            String key,
            List<Map<String, Object>> sectorIndicators,
            List<Map<String, Object>> macroIndicators,
            int totalCandidates) {
        put(key, sectorIndicators, macroIndicators, totalCandidates, 0L);
    }

    public void put(
            String key,
            List<Map<String, Object>> sectorIndicators,
            List<Map<String, Object>> macroIndicators,
            int totalCandidates,
            long computeTimeMs) {
        put(key, sectorIndicators, macroIndicators, totalCandidates, computeTimeMs, Map.of());
    }

    public void put(
            String key,
            List<Map<String, Object>> sectorIndicators,
            List<Map<String, Object>> macroIndicators,
            int totalCandidates,
            long computeTimeMs,
            Map<String, Integer> sourceCandidateCounts) {
        synchronized (cache) {
            cache.put(
                    key,
                    new CachedEntry(
                            sectorIndicators,
                            macroIndicators,
                            totalCandidates,
                            System.currentTimeMillis(),
                            Math.max(0L, computeTimeMs),
                            sourceCandidateCounts == null ? Map.of() : Map.copyOf(sourceCandidateCounts)));
        }
    }

    private static String safe(Supplier<String> supplier) {
        try {
            String value = supplier.get();
            return value == null ? "" : value;
        } catch (Exception ex) {
            return "";
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public record CachedEntry(
            List<Map<String, Object>> sectorIndicators,
            List<Map<String, Object>> macroIndicators,
            int totalCandidates,
            long computedAtMs,
            long computeTimeMs,
            Map<String, Integer> sourceCandidateCounts) {
        public CachedEntry(
                List<Map<String, Object>> sectorIndicators,
                List<Map<String, Object>> macroIndicators,
                int totalCandidates,
                long computedAtMs) {
            this(sectorIndicators, macroIndicators, totalCandidates, computedAtMs, 0L, Map.of());
        }

        public CachedEntry(
                List<Map<String, Object>> sectorIndicators,
                List<Map<String, Object>> macroIndicators,
                int totalCandidates,
                long computedAtMs,
                long computeTimeMs) {
            this(sectorIndicators, macroIndicators, totalCandidates, computedAtMs, computeTimeMs, Map.of());
        }
    }
}
