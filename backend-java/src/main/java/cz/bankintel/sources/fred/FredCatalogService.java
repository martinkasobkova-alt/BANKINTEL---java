package cz.bankintel.sources.fred;
import cz.bankintel.util.BankIntelEnvVars;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Service;

@Service
public class FredCatalogService {

    private static final String FRED_API_ROOT = "https://api.stlouisfed.org/fred";
    private static final long ROOT_TTL_MS = Duration.ofHours(24).toMillis();
    private static final long EXPAND_TTL_MS = Duration.ofHours(1).toMillis();
    private static final int EXPAND_LIMIT = 1000;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ReentrantLock cacheLock = new ReentrantLock();

    private Map<String, Object> rootCache;
    private long rootFetchedAtMs;
    private final Map<Integer, ExpandCacheEntry> expandCache = new HashMap<>();

    public FredCatalogService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public boolean hasApiKey() {
        return !fredApiKey().isBlank();
    }

    public String fredApiKey() {
        String key = BankIntelEnvVars.get("FRED_API_KEY");
        return key != null ? key.trim() : "";
    }

    public Map<String, Object> missingKeyPayload() {
        return Map.of(
                "error", "FRED_API_KEY is missing on backend",
                "code", "FRED_API_KEY_MISSING",
                "source", "FRED",
                "configured", false,
                "fred_api_key_configured", false,
                "fred_api_configured", false);
    }

    public long rootTtlSeconds() {
        return ROOT_TTL_MS / 1000;
    }

    public long expandTtlSeconds() {
        return EXPAND_TTL_MS / 1000;
    }

    public double rootFetchedAtEpochSeconds() {
        cacheLock.lock();
        try {
            return rootFetchedAtMs / 1000.0;
        } finally {
            cacheLock.unlock();
        }
    }

    public double httpTimeoutSeconds() {
        String raw = BankIntelEnvVars.get("FRED_HTTP_TIMEOUT_SEC");
        if (raw == null || raw.isBlank()) {
            raw = BankIntelEnvVars.get("FRED_HTTP_TIMEOUT");
        }
        if (raw == null || raw.isBlank()) {
            return 12.0;
        }
        double sec;
        try {
            sec = Double.parseDouble(raw.trim());
        } catch (NumberFormatException ex) {
            sec = 12.0;
        }
        return Math.max(10.0, Math.min(15.0, sec));
    }

    public Map<String, Object> getRootCatalog(boolean forceRefresh) throws IOException, InterruptedException {
        long now = System.currentTimeMillis();
        cacheLock.lock();
        try {
            if (!forceRefresh && rootCache != null && (now - rootFetchedAtMs) < ROOT_TTL_MS) {
                return new LinkedHashMap<>(rootCache);
            }
        } finally {
            cacheLock.unlock();
        }

        Map<String, Object> payload = getJson("category/children", Map.of("category_id", 0));
        List<Map<String, Object>> categories = parseCategoryRows(payload.get("categories"));
        categories.sort(Comparator.comparing(row -> String.valueOf(row.get("name")).toLowerCase()));

        List<Map<String, Object>> sets = new ArrayList<>();
        for (Map<String, Object> category : categories) {
            int id = (int) category.get("id");
            String name = String.valueOf(category.get("name"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", "CAT||" + id);
            row.put("set_id", "CAT||" + id);
            row.put("name", name + " (" + id + ")");
            row.put("full_path", "FRED > " + name);
            row.put("kind", "category");
            row.put("fred_category_id", String.valueOf(id));
            row.put("period", "");
            row.put("territory", "USA / FRED");
            row.put("last_update", "");
            sets.add(row);
        }

        Map<String, Object> categoryBucket = new LinkedHashMap<>();
        categoryBucket.put("name", "FRED — hlavní kategorie (úroveň 1 · " + categories.size() + " větví z API)");
        categoryBucket.put("path", "FRED");
        categoryBucket.put("children", List.of());
        categoryBucket.put("sets", sets);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("categories", List.of(categoryBucket));
        result.put("total_sets", sets.size());
        result.put("source", "live");
        result.put("mode", "fred_categories_l1");
        result.put("fred_api_configured", true);
        result.put("fred_api_key_configured", true);
        result.put(
                "fred_root_note",
                "Kořen FRED vrací jen několik názvů kategorií; statisíce řad jsou uvnitř po rozbalení.");

        cacheLock.lock();
        try {
            rootCache = new LinkedHashMap<>(result);
            rootFetchedAtMs = now;
        } finally {
            cacheLock.unlock();
        }
        return result;
    }

    public Map<String, Object> expandCategory(int categoryId, boolean forceRefresh)
            throws IOException, InterruptedException {
        long now = System.currentTimeMillis();
        cacheLock.lock();
        try {
            if (forceRefresh) {
                expandCache.remove(categoryId);
            } else {
                ExpandCacheEntry cached = expandCache.get(categoryId);
                if (cached != null && (now - cached.cachedAtMs()) < EXPAND_TTL_MS) {
                    return new LinkedHashMap<>(cached.payload());
                }
            }
        } finally {
            cacheLock.unlock();
        }

        Map<String, Object> info = getJson("category", Map.of("category_id", categoryId));
        String title = categoryTitle(info, categoryId);

        Map<String, Object> childrenResp = getJson("category/children", Map.of("category_id", categoryId));
        List<Map<String, Object>> subcats = parseCategoryRows(childrenResp.get("categories"));
        subcats.sort(Comparator.comparing(row -> String.valueOf(row.get("name")).toLowerCase()));

        List<Map<String, Object>> seriesSets = new ArrayList<>();
        List<Map<String, Object>> seriesNormalized = new ArrayList<>();
        int offset = 0;
        while (true) {
            Map<String, Object> batchResp =
                    getJson("category/series", Map.of("category_id", categoryId, "limit", EXPAND_LIMIT, "offset", offset));
            List<Map<String, Object>> batch = parseMapRows(batchResp.get("seriess"));
            for (Map<String, Object> row : batch) {
                String seriesId = stringOrBlank(row.get("id"));
                if (seriesId.isBlank()) {
                    continue;
                }
                String seriesTitle = stringOrBlank(row.get("title"));
                if (seriesTitle.isBlank()) {
                    seriesTitle = seriesId;
                }
                String frequency = stringOrBlank(row.get("frequency_short"));
                if (frequency.isBlank()) {
                    frequency = stringOrBlank(row.get("frequency"));
                }
                String units = stringOrBlank(row.get("units"));

                Map<String, Object> normalized = new LinkedHashMap<>();
                normalized.put("id", seriesId);
                normalized.put("title", seriesTitle);
                normalized.put("frequency_short", frequency);
                normalized.put("units", units);
                seriesNormalized.add(normalized);

                String displayTitle = seriesTitle.length() > 180 ? seriesTitle.substring(0, 180) + "…" : seriesTitle;
                Map<String, Object> set = new LinkedHashMap<>();
                set.put("id", seriesId);
                set.put("set_id", seriesId);
                set.put("name", displayTitle + " (" + seriesId + ")");
                set.put("full_path", "FRED > " + title + " > " + seriesId);
                set.put("kind", "selection");
                set.put("dataset_code", String.valueOf(categoryId));
                set.put("dataset_name", title);
                set.put("fred_series_id", seriesId);
                set.put("period", frequency.isBlank() ? "dle řady" : frequency);
                set.put("territory", "USA / FRED");
                set.put("last_update", "");
                seriesSets.add(set);
            }
            if (batch.size() < EXPAND_LIMIT) {
                break;
            }
            offset += EXPAND_LIMIT;
        }

        List<Map<String, Object>> subcatRows = new ArrayList<>();
        for (Map<String, Object> subcat : subcats) {
            int id = (int) subcat.get("id");
            String name = String.valueOf(subcat.get("name"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", "CAT||" + id);
            row.put("set_id", "CAT||" + id);
            row.put("name", name + " (" + id + ")");
            row.put("full_path", "FRED > " + title + " > " + name);
            row.put("kind", "category");
            row.put("fred_category_id", String.valueOf(id));
            row.put("period", "");
            row.put("territory", "USA / FRED");
            row.put("last_update", "");
            subcatRows.add(row);
        }

        seriesSets.sort(Comparator.comparing(row -> stringOrBlank(row.get("fred_series_id")).toLowerCase()));
        seriesNormalized.sort(Comparator.comparing(row -> stringOrBlank(row.get("id")).toLowerCase()));
        List<Map<String, Object>> merged = new ArrayList<>(subcatRows);
        merged.addAll(seriesSets);

        Map<String, Object> categoryNode = new LinkedHashMap<>();
        categoryNode.put("name", title + " (" + categoryId + ")");
        categoryNode.put("path", "FRED::" + categoryId);
        categoryNode.put("children", List.of());
        categoryNode.put("sets", merged);

        List<Map<String, Object>> childList = new ArrayList<>();
        for (Map<String, Object> subcat : subcats) {
            childList.add(Map.of("id", subcat.get("id"), "name", subcat.get("name")));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("categories", List.of(categoryNode));
        result.put("total_sets", merged.size());
        result.put("category_id", categoryId);
        result.put("fred_category_id", categoryId);
        result.put("children", childList);
        result.put("series", seriesNormalized);
        result.put("source", "live");

        cacheLock.lock();
        try {
            expandCache.put(categoryId, new ExpandCacheEntry(now, new LinkedHashMap<>(result)));
        } finally {
            cacheLock.unlock();
        }
        return result;
    }

    public void invalidateCaches() {
        cacheLock.lock();
        try {
            rootCache = null;
            rootFetchedAtMs = 0;
            expandCache.clear();
        } finally {
            cacheLock.unlock();
        }
    }

    public Map<String, Object> getCategoryChildren(int categoryId) throws IOException, InterruptedException {
        Map<String, Object> payload = getJson("category/children", Map.of("category_id", categoryId));
        payload.put("fred_api_key_configured", true);
        payload.put("fred_api_configured", true);
        payload.put("source", "FRED");
        return payload;
    }

    public Map<String, Object> getCategorySeries(int categoryId, int limit, int offset)
            throws IOException, InterruptedException {
        int lim = Math.max(1, Math.min(limit, 1000));
        int off = Math.max(0, offset);
        Map<String, Object> payload =
                getJson("category/series", Map.of("category_id", categoryId, "limit", lim, "offset", off));
        payload.put("fred_api_key_configured", true);
        payload.put("fred_api_configured", true);
        payload.put("source", "FRED");
        return payload;
    }

    public Map<String, Object> getSeriesObservations(String seriesId, int limit)
            throws IOException, InterruptedException {
        String sid = seriesId != null ? seriesId.trim() : "";
        if (sid.isBlank()) {
            throw new IllegalArgumentException("Chybí series_id.");
        }
        int lim = Math.max(1, Math.min(limit, 100_000));
        Map<String, Object> payload = getJson("series/observations", Map.of("series_id", sid, "limit", lim));
        payload.put("fred_api_key_configured", true);
        payload.put("fred_api_configured", true);
        payload.put("source", "FRED");
        return payload;
    }

    public Map<String, Object> proxyMeta() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("external_root", FRED_API_ROOT);
        out.put("fred_api_key_configured", hasApiKey());
        out.put("fred_http_timeout_sec", httpTimeoutSeconds());
        out.put(
                "mapped_endpoints",
                List.of(
                        "GET /api/fred/search → fred/series/search",
                        "GET /api/fred/category/{id}/children → fred/category/children",
                        "GET /api/fred/category/{id}/series → fred/category/series",
                        "GET /api/fred/series/{series_id}/observations → fred/series/observations"));
        return out;
    }

    public Map<String, Object> searchSeries(String query, int limit) throws IOException, InterruptedException {
        if (!hasApiKey()) {
            return missingKeyPayload();
        }
        String q = query != null ? query.trim() : "";
        if (q.isBlank()) {
            throw new IllegalArgumentException("search_text is required");
        }
        int lim = Math.max(1, Math.min(limit, 1000));
        Map<String, Object> payload =
                getJson(
                        "series/search",
                        Map.of("search_text", q, "limit", lim, "order_by", "search_rank", "sort_order", "desc"));
        Map<String, Object> out = new LinkedHashMap<>(payload);
        out.put("fred_api_key_configured", true);
        out.put("fred_api_configured", true);
        out.put("source", "FRED");
        return out;
    }

    private Map<String, Object> getJson(String path, Map<String, Object> params)
            throws IOException, InterruptedException {
        String key = fredApiKey();
        if (key.isBlank()) {
            throw new IllegalStateException("FRED_API_KEY missing");
        }
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("api_key", key);
        query.put("file_type", "json");
        query.putAll(params);
        URI uri = URI.create(FRED_API_ROOT + "/" + path + "?" + toQuery(query));

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(uri)
                        .timeout(Duration.ofMillis((long) (httpTimeoutSeconds() * 1000)))
                        .GET()
                        .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            String detail = response.body() != null ? response.body() : "";
            throw new IOException("FRED API " + response.statusCode() + ": " + detail);
        }
        return objectMapper.readValue(response.body(), new TypeReference<>() {});
    }

    private static String toQuery(Map<String, Object> params) {
        List<String> pairs = new ArrayList<>();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            String key = URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8);
            String value = URLEncoder.encode(String.valueOf(entry.getValue()), StandardCharsets.UTF_8);
            pairs.add(key + "=" + value);
        }
        return String.join("&", pairs);
    }

    private static String categoryTitle(Map<String, Object> info, int categoryId) {
        List<Map<String, Object>> rows = parseMapRows(info.get("categories"));
        if (!rows.isEmpty()) {
            String title = stringOrBlank(rows.get(0).get("name"));
            if (!title.isBlank()) {
                return title;
            }
        }
        return String.valueOf(categoryId);
    }

    private static List<Map<String, Object>> parseCategoryRows(Object raw) {
        List<Map<String, Object>> rows = parseMapRows(raw);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object idObj = row.get("id");
            if (idObj == null) {
                continue;
            }
            int id;
            try {
                id = Integer.parseInt(String.valueOf(idObj));
            } catch (NumberFormatException ex) {
                continue;
            }
            String name = stringOrBlank(row.get("name"));
            if (name.isBlank()) {
                name = String.valueOf(id);
            }
            out.add(Map.of("id", id, "name", name));
        }
        return out;
    }

    private static List<Map<String, Object>> parseMapRows(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) map;
                out.add(cast);
            }
        }
        return out;
    }

    private static String stringOrBlank(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    private record ExpandCacheEntry(long cachedAtMs, Map<String, Object> payload) {}
}
