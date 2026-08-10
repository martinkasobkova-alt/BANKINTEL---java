package cz.bankintel.sources.data360;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.domain.dto.SourceDtos.SourceCreateRequest;
import cz.bankintel.service.sources.SourceService;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class Data360CatalogService {

    private static final String DATA360_API_ROOT = "https://data360api.worldbank.org";
    private static final String WORLD_BANK_API_ROOT = "https://api.worldbank.org";
    private static final long CATALOG_TTL_MS = 60_000L;
    private static final long COUNTRIES_TTL_MS = Duration.ofHours(12).toMillis();
    private static final long COUNTRY_ROWS_TTL_MS = Duration.ofHours(2).toMillis();

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final SourceService sourceService;

    private final ReentrantLock cacheLock = new ReentrantLock();
    private CachedPayload searchCatalogCache;
    private CachedPayload countriesTreeCache;
    private final Map<String, CachedPayload> countryIndicatorsCache = new LinkedHashMap<>();

    public Data360CatalogService(ObjectMapper objectMapper, SourceService sourceService) {
        this.objectMapper = objectMapper;
        this.sourceService = sourceService;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public Map<String, Object> getCatalog(String q, int top) {
        if (stringOrBlank(q).isBlank()) {
            return countriesCatalogTree();
        }
        return searchCatalog(q, top);
    }

    public Map<String, Object> refreshCatalog(String q, int top) {
        cacheLock.lock();
        try {
            searchCatalogCache = null;
            countriesTreeCache = null;
            countryIndicatorsCache.clear();
        } finally {
            cacheLock.unlock();
        }
        Map<String, Object> payload = getCatalog(q, top);
        payload = new LinkedHashMap<>(payload);
        payload.put("cached", false);
        return payload;
    }

    public Map<String, Object> listIndicatorsForDataset(String datasetId) {
        String ds = stringOrBlank(datasetId);
        if (ds.length() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "datasetId musi mit aspon 2 znaky.");
        }
        URI uri = URI.create(DATA360_API_ROOT + "/data360/indicators?datasetId=" + urlEncode(ds));
        try {
            Map<String, Object> payload = getJson(uri, Duration.ofSeconds(60));
            Object raw = payload.get("value");
            List<?> values = raw instanceof List<?> list ? list : List.of();
            if (values.isEmpty()) {
                Object alt = payload.get("Value");
                values = alt instanceof List<?> list ? list : List.of();
            }
            List<String> indicators = new ArrayList<>();
            for (Object value : values) {
                String v = stringOrBlank(value);
                if (!v.isBlank()) {
                    indicators.add(v);
                }
            }
            return Map.of("datasetId", ds, "indicators", indicators);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Data360 indicators upstream error");
        }
    }

    public Map<String, Object> metadataQuery(Map<String, Object> body) {
        String query = stringOrBlank(body.get("query"));
        if (query.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vyzaduji JSON { query: string }.");
        }
        Map<String, Object> req = Map.of("query", query);
        URI uri = URI.create(DATA360_API_ROOT + "/data360/metadata");
        try {
            return postJson(uri, req, Duration.ofSeconds(90));
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Data360 metadata upstream error");
        }
    }

    public Map<String, Object> getCountryIndicators(String countryCode) {
        String code = stringOrBlank(countryCode).toUpperCase(Locale.ROOT);
        if (code.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chybi country code.");
        }

        long now = System.currentTimeMillis();
        cacheLock.lock();
        try {
            CachedPayload cached = countryIndicatorsCache.get(code);
            if (cached != null && (now - cached.fetchedAtMs()) < COUNTRY_ROWS_TTL_MS) {
                return new LinkedHashMap<>(cached.payload());
            }
        } finally {
            cacheLock.unlock();
        }

        String countryName = countryNameFromCode(code);
        if (countryName == null || countryName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Neznamy country code: " + code);
        }
        List<Map<String, Object>> rows = fetchCountryRows(countryName, code);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("country", code);
        payload.put("rows", rows);
        payload.put("count", rows.size());
        payload.put("country_node", buildCountryNode(countryName, code, rows));
        payload.put("data360_availability_filtered", false);
        payload.put("data360_browse_source", "search");

        cacheLock.lock();
        try {
            countryIndicatorsCache.put(code, new CachedPayload(now, new LinkedHashMap<>(payload)));
        } finally {
            cacheLock.unlock();
        }
        return payload;
    }

    public Map<String, Object> addSource(Map<String, Object> payload) {
        String sid = stringOrBlank(payload.get("set_id"));
        Map<String, Object> queryParams = mergeQueryParams(payload);
        if (stringOrBlank(queryParams.get("DATABASE_ID")).isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Pro World Bank Data360 je povinny DATABASE_ID (nebo validni set_id).");
        }

        String displayName = stringOrBlank(payload.get("name"));
        if (displayName.isBlank()) {
            displayName = "World Bank Data360 · " + sid;
        }
        Integer refreshInterval = toInteger(payload.get("refresh_interval_minutes"));
        if (refreshInterval == null) {
            refreshInterval = 1440;
        }
        Boolean active = toBoolean(payload.get("active"));
        if (active == null) {
            active = true;
        }

        SourceCreateRequest request = new SourceCreateRequest(
                displayName,
                "world_bank_data360",
                DATA360_API_ROOT,
                "/data360/data",
                "GET",
                "none",
                null,
                Map.of("Accept", "application/json", "User-Agent", "banking-bi/1.0"),
                queryParams,
                refreshInterval,
                active,
                displayName,
                null);
        Map<String, Object> created = sourceService.createSource(request);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", created.get("id"));
        out.put("name", displayName);
        out.put("set_id", sid.isBlank()
                ? stringOrBlank(queryParams.get("DATABASE_ID")) + "|" + stringOrBlank(queryParams.get("INDICATOR"))
                : sid);
        out.put("query_params", queryParams);
        out.put("data360_identifier_override_used", false);
        out.put("data360_identifier_suppressed_warning_cs", null);
        return out;
    }

    private Map<String, Object> countriesCatalogTree() {
        long now = System.currentTimeMillis();
        cacheLock.lock();
        try {
            if (countriesTreeCache != null && (now - countriesTreeCache.fetchedAtMs()) < COUNTRIES_TTL_MS) {
                return new LinkedHashMap<>(countriesTreeCache.payload());
            }
        } finally {
            cacheLock.unlock();
        }

        List<Map<String, String>> countries = fetchWorldBankCountries();
        Map<String, List<Map<String, String>>> groups = new LinkedHashMap<>();
        for (Map<String, String> country : countries) {
            String name = stringOrBlank(country.get("name"));
            String letter = name.isBlank() ? "#" : name.substring(0, 1).toUpperCase(Locale.ROOT);
            if (!letter.matches("[A-Z]")) {
                letter = "#";
            }
            groups.computeIfAbsent(letter, ignored -> new ArrayList<>()).add(country);
        }

        List<String> letters = new ArrayList<>(groups.keySet());
        letters.sort((a, b) -> {
            if (a.equals("#")) {
                return 1;
            }
            if (b.equals("#")) {
                return -1;
            }
            return a.compareTo(b);
        });

        List<Map<String, Object>> letterNodes = new ArrayList<>();
        for (String letter : letters) {
            List<Map<String, String>> values = groups.getOrDefault(letter, List.of());
            values.sort(Comparator.comparing(v -> stringOrBlank(v.get("name")).toLowerCase(Locale.ROOT)));

            List<Map<String, Object>> countryNodes = new ArrayList<>();
            for (Map<String, String> country : values) {
                countryNodes.add(Map.of(
                        "name", country.get("name"),
                        "path", "World Bank Data360 > " + letter + " > " + country.get("name"),
                        "children", List.of(),
                        "sets", List.of(),
                        "data360_country", country.get("iso3"),
                        "data360_country_name", country.get("name"),
                        "data360_country_lazy", true));
            }
            letterNodes.add(Map.of(
                    "name", letter + " (" + values.size() + ")",
                    "path", "World Bank Data360 > " + letter,
                    "children", countryNodes,
                    "sets", List.of()));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("categories", List.of(Map.of(
                "name", "World Bank Data360 — economies",
                "path", "World Bank Data360",
                "children", letterNodes,
                "sets", List.of())));
        payload.put("total_sets", 0);
        payload.put("source", "live");
        payload.put("provider", "world_bank_data360");
        payload.put("data360_browse_mode", "countries_first");
        payload.put("fetched_at", now / 1000.0);
        payload.put("ttl_seconds", COUNTRIES_TTL_MS / 1000);

        cacheLock.lock();
        try {
            countriesTreeCache = new CachedPayload(now, new LinkedHashMap<>(payload));
        } finally {
            cacheLock.unlock();
        }
        return payload;
    }

    private Map<String, Object> searchCatalog(String q, int top) {
        String phrase = stringOrBlank(q);
        int limit = Math.max(1, Math.min(top, 200));
        String key = phrase + "::" + limit;
        long now = System.currentTimeMillis();
        cacheLock.lock();
        try {
            if (searchCatalogCache != null
                    && (now - searchCatalogCache.fetchedAtMs()) < CATALOG_TTL_MS
                    && key.equals(searchCatalogCache.key())) {
                return new LinkedHashMap<>(searchCatalogCache.payload());
            }
        } finally {
            cacheLock.unlock();
        }

        List<Map<String, Object>> rows;
        Map<String, Object> meta;
        List<String> errors = new ArrayList<>();
        try {
            SearchResult result = data360SearchRows(phrase, limit, 0);
            rows = result.rows();
            meta = result.meta();
        } catch (Exception ex) {
            rows = List.of();
            meta = Map.of("error", stringOrBlank(ex.getMessage()));
            errors.add(
                    "Data360 API Svetove banky neni dostupne (sit nebo chyba serveru). Zkuste to prosim znovu pozdeji.");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("categories", List.of(Map.of(
                "name", "World Bank (vyhledavani rad)",
                "path", "WB_DATA360",
                "children", List.of(),
                "sets", rows)));
        payload.put("total_sets", rows.size());
        payload.put("source", rows.isEmpty() && !errors.isEmpty() ? "error" : "live");
        payload.put("data360_boost_phrase", phrase);
        payload.put("errors", errors);
        payload.put("data360_meta", meta);
        payload.put("provider", "world_bank_data360");
        payload.put("fetched_at", now / 1000.0);
        payload.put("ttl_seconds", CATALOG_TTL_MS / 1000);

        cacheLock.lock();
        try {
            searchCatalogCache = new CachedPayload(now, key, new LinkedHashMap<>(payload));
        } finally {
            cacheLock.unlock();
        }
        return payload;
    }

    private List<Map<String, Object>> fetchCountryRows(String countryName, String countryCode) {
        Set<String> seenSetIds = new LinkedHashSet<>();
        List<Map<String, Object>> merged = new ArrayList<>();
        for (String phrase : countrySearchPhrases(countryName, countryCode)) {
            try {
                SearchResult result = data360SearchRows(phrase, 200, 0);
                for (Map<String, Object> row : result.rows()) {
                    String setId = stringOrBlank(row.get("set_id"));
                    if (setId.isBlank() || seenSetIds.contains(setId)) {
                        continue;
                    }
                    seenSetIds.add(setId);
                    Map<String, Object> qp = asMap(row.get("query_params"));
                    Map<String, Object> mergedQp = new LinkedHashMap<>(qp);
                    mergedQp.put("REF_AREA", countryCode);
                    Map<String, Object> out = new LinkedHashMap<>(row);
                    out.put(
                            "name",
                            countryName + " · " + stringOrBlank(row.get("data360_series_name")).replaceAll("^\\s*$", stringOrBlank(row.get("name"))));
                    out.put("query_params", mergedQp);
                    out.put("data360_country", countryCode);
                    out.put("data360_country_name", countryName);
                    merged.add(out);
                    if (merged.size() >= 2100) {
                        return merged;
                    }
                }
            } catch (Exception ignored) {
                // Keep country browse robust even when individual phrase lookups fail.
            }
        }
        return merged;
    }

    private List<String> countrySearchPhrases(String countryName, String code) {
        List<String> candidates = List.of(
                countryName,
                countryName + " indicators",
                countryName + " economy indicators",
                countryName + " World Bank",
                code,
                code + " indicators",
                "World Bank WDI population poverty GDP indicators",
                "world bank",
                "indicator",
                "world bank indicators",
                "world bank data360 indicators");
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String phrase : candidates) {
            String p = stringOrBlank(phrase);
            if (!p.isBlank()) {
                out.add(p);
            }
        }
        return new ArrayList<>(out);
    }

    private Map<String, Object> buildCountryNode(String countryName, String code, List<Map<String, Object>> rows) {
        List<Map<String, Object>> sets = new ArrayList<>(rows);
        sets.sort(Comparator.comparing(v -> stringOrBlank(v.get("name")).toLowerCase(Locale.ROOT)));
        return Map.of(
                "name", countryName,
                "path", "World Bank Data360 > " + countryName,
                "children", List.of(),
                "sets", sets,
                "data360_country", code,
                "data360_country_name", countryName,
                "data360_country_hierarchy", true);
    }

    private List<Map<String, String>> fetchWorldBankCountries() {
        URI uri = URI.create(WORLD_BANK_API_ROOT + "/v2/country?format=json&per_page=400");
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(40))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("HTTP " + response.statusCode());
            }
            List<Object> payload = objectMapper.readValue(response.body(), new TypeReference<>() {});
            if (payload.size() < 2) {
                return List.of();
            }
            Object rowsRaw = payload.get(1);
            if (!(rowsRaw instanceof List<?> rows)) {
                return List.of();
            }
            List<Map<String, String>> out = new ArrayList<>();
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?> item)) {
                    continue;
                }
                String iso3 = stringOrBlank(item.get("id")).toUpperCase(Locale.ROOT);
                String name = stringOrBlank(item.get("name"));
                Map<String, Object> region = asMap(item.get("region"));
                String regionName = stringOrBlank(region.get("value"));
                if (iso3.isBlank() || name.isBlank() || "aggregates".equalsIgnoreCase(regionName)) {
                    continue;
                }
                out.add(Map.of("iso3", iso3, "name", name));
            }
            out.sort(Comparator.comparing(v -> stringOrBlank(v.get("name")).toLowerCase(Locale.ROOT)));
            return out;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String countryNameFromCode(String code) {
        for (Map<String, String> country : fetchWorldBankCountries()) {
            if (code.equalsIgnoreCase(stringOrBlank(country.get("iso3")))) {
                return stringOrBlank(country.get("name"));
            }
        }
        return null;
    }

    private SearchResult data360SearchRows(String searchPhrase, int top, int skip) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", true);
        body.put(
                "select",
                "series_description/idno, series_description/name, series_description/database_id, series_description/time_periods");
        body.put("search", stringOrBlank(searchPhrase).isBlank() ? "World Bank" : searchPhrase.trim());
        body.put("top", Math.max(1, Math.min(top, 200)));
        body.put("skip", Math.max(0, skip));

        URI uri = URI.create(DATA360_API_ROOT + "/data360/searchv2");
        Map<String, Object> raw = postJson(uri, body, Duration.ofSeconds(90));
        Object valueObj = raw.get("value");
        List<?> values = valueObj instanceof List<?> list ? list : List.of();
        if (values.isEmpty()) {
            Object alt = raw.get("Value");
            values = alt instanceof List<?> list ? list : List.of();
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> hit)) {
                continue;
            }
            Map<String, Object> row = mapSearchHitToCatalogSet(asMap(hit));
            if (row != null) {
                rows.add(row);
            }
        }
        Map<String, Object> meta = Map.of(
                "count", raw.getOrDefault("@odata.count", raw.get("odata_count")),
                "search_body", body);
        return new SearchResult(rows, meta);
    }

    private Map<String, Object> mapSearchHitToCatalogSet(Map<String, Object> hit) {
        Map<String, Object> sd = asMap(hit.get("series_description"));
        if (sd.isEmpty()) {
            sd = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : hit.entrySet()) {
                String key = stringOrBlank(entry.getKey());
                if (key.startsWith("series_description/")) {
                    sd.put(key.substring("series_description/".length()), entry.getValue());
                }
            }
        }
        String db = stringOrBlank(sd.get("database_id"));
        if (db.isBlank()) {
            db = stringOrBlank(sd.get("databaseId"));
        }
        String ind = stringOrBlank(sd.get("idno"));
        if (ind.isBlank()) {
            ind = stringOrBlank(sd.get("id"));
        }
        String title = stringOrBlank(sd.get("name"));
        if (title.isBlank()) {
            title = !ind.isBlank() ? ind : (!db.isBlank() ? db : "Data360");
        }
        if (db.isBlank() && !ind.isBlank()) {
            db = ind.contains("_") ? ind.split("_", 2)[0] : "UNKNOWN";
        }
        if (db.isBlank() || ind.isBlank()) {
            return null;
        }
        String setId = db + "|" + ind;

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", setId);
        row.put("set_id", setId);
        row.put("name", title + " · " + ind + " (" + db + ")");
        row.put("full_path", "World Bank > " + db + " > " + title);
        row.put("kind", "selection");
        row.put("provider", "world_bank_data360");
        row.put("dataset_code", db);
        row.put("dataset_name", title);
        row.put("data360_database_id", db);
        row.put("data360_indicator", ind);
        row.put("data360_series_name", title);
        row.put("provider_label", "World Bank");
        row.put("period", "");
        row.put("territory", "Data360");
        row.put("last_update", coverageEnd(sd));
        row.put("query_params", Map.of("DATABASE_ID", db, "INDICATOR", ind, "skip", "0"));
        row.put("raw_search_hit", hit);
        return row;
    }

    private static String coverageEnd(Map<String, Object> seriesDescription) {
        Object tpsObj = seriesDescription.get("time_periods");
        if (!(tpsObj instanceof List<?> tps)) {
            return "";
        }
        String best = "";
        for (Object tp : tps) {
            if (!(tp instanceof Map<?, ?> item)) {
                continue;
            }
            String end = stringOrBlank(item.get("end"));
            if (!end.isBlank() && end.compareTo(best) > 0) {
                best = end;
            }
        }
        return best;
    }

    private Map<String, Object> mergeQueryParams(Map<String, Object> payload) {
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("skip", "0");

        String sid = stringOrBlank(payload.get("set_id"));
        String sidDb = "";
        String sidIndicator = "";
        if (sid.contains("|")) {
            String[] split = sid.split("\\|", 2);
            sidDb = split[0].trim();
            sidIndicator = split.length > 1 ? split[1].trim() : "";
        }

        Object qpObj = payload.get("query_params");
        Map<String, Object> qp = asMap(qpObj);
        String qpDb = stringOrBlank(qp.get("DATABASE_ID"));
        String qpIndicator = stringOrBlank(qp.get("INDICATOR"));

        if (!sidDb.isBlank()) {
            merged.put("DATABASE_ID", sidDb);
        } else if (!qpDb.isBlank()) {
            merged.put("DATABASE_ID", qpDb);
        }
        if (!sidIndicator.isBlank()) {
            merged.put("INDICATOR", sidIndicator);
        } else if (!qpIndicator.isBlank()) {
            merged.put("INDICATOR", qpIndicator);
        }

        Set<String> allow = Set.of(
                "REF_AREA", "FREQ", "TIME_PERIOD", "timePeriodFrom", "timePeriodTo", "SEX", "AGE",
                "URBANISATION", "COMP_BREAKDOWN_1", "COMP_BREAKDOWN_2", "COMP_BREAKDOWN_3",
                "UNIT_MEASURE", "UNIT_TYPE", "UNIT_MULT", "skip");
        for (Map.Entry<String, Object> entry : qp.entrySet()) {
            String key = stringOrBlank(entry.getKey());
            if (key.equals("DATABASE_ID") || key.equals("INDICATOR") || !allow.contains(key)) {
                continue;
            }
            if (entry.getValue() == null) {
                continue;
            }
            String value = stringOrBlank(entry.getValue());
            if (value.isBlank()) {
                continue;
            }
            merged.put(key, value);
        }

        if (stringOrBlank(merged.get("INDICATOR")).isBlank()) {
            merged.remove("INDICATOR");
        }
        return merged;
    }

    private Map<String, Object> getJson(URI uri, Duration timeout) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("User-Agent", "banking-bi/1.0")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return objectMapper.readValue(response.body(), new TypeReference<>() {});
    }

    private Map<String, Object> postJson(URI uri, Map<String, Object> body, Duration timeout)
            throws IOException, InterruptedException {
        String requestBody = objectMapper.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", "banking-bi/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return objectMapper.readValue(response.body(), new TypeReference<>() {});
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String stringOrBlank(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    private static Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Boolean toBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String raw = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(raw) || "1".equals(raw) || "yes".equals(raw) || "on".equals(raw)) {
            return true;
        }
        if ("false".equals(raw) || "0".equals(raw) || "no".equals(raw) || "off".equals(raw)) {
            return false;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private record SearchResult(List<Map<String, Object>> rows, Map<String, Object> meta) {}

    private record CachedPayload(long fetchedAtMs, String key, Map<String, Object> payload) {
        CachedPayload(long fetchedAtMs, Map<String, Object> payload) {
            this(fetchedAtMs, "", payload);
        }
    }
}
