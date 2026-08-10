package cz.bankintel.search;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * LRU cache for catalog FTS search results — ref Python search-latency-scoring-cache-fix
 * (fold_ascii + parse_ecb_set_id keys).
 */
@Component
public class CatalogSearchResultCache {

    private static final int MAX_ENTRIES = 384;

    private final Map<String, List<Map<String, Object>>> cache =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<Map<String, Object>>> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    public List<Map<String, Object>> getOrCompute(String source, String queryRaw, int limit, Supplier<List<Map<String, Object>>> loader) {
        String key = cacheKey("default", source, queryRaw, limit);
        synchronized (cache) {
            List<Map<String, Object>> hit = cache.get(key);
            if (hit != null) {
                return hit;
            }
        }
        List<Map<String, Object>> loaded = loader.get();
        synchronized (cache) {
            cache.put(key, loaded);
            return loaded;
        }
    }

    public void warmPut(String source, String queryRaw, int limit, List<Map<String, Object>> rows) {
        synchronized (cache) {
            cache.put(cacheKey("default", source, queryRaw, limit), rows);
        }
    }

    public Optional<List<Map<String, Object>>> get(String namespace, String source, String queryRaw, int limit) {
        synchronized (cache) {
            return Optional.ofNullable(cache.get(cacheKey(namespace, source, queryRaw, limit)));
        }
    }

    public void put(String namespace, String source, String queryRaw, int limit, List<Map<String, Object>> rows) {
        synchronized (cache) {
            cache.put(cacheKey(namespace, source, queryRaw, limit), rows);
        }
    }

    static String cacheKey(String source, String queryRaw, int limit) {
        return cacheKey("default", source, queryRaw, limit);
    }

    static String cacheKey(String namespace, String source, String queryRaw, int limit) {
        String src = CatalogSourceRegistry.normalizeSearchSource(source);
        String q = CatalogTextUtils.foldAscii(queryRaw == null ? "" : queryRaw.trim());
        String ecbKey = CatalogSearchVariantDedup.parseEcbSetIdKey(q);
        return String.valueOf(namespace) + "|" + src + "|" + q + "|" + ecbKey + "|" + limit;
    }
}
