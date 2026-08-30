package cz.bankintel.connector;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.CatalogTextUtils;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * CSU DataStat connector.
 *
 * <p>Catalog previews use the dataset POST endpoint, because the predefined
 * selection endpoint often returns only one current-period snapshot. Saved
 * sources keep using the fast selection endpoint unless they explicitly ask for
 * a full dataset via query_params.csu_full_dataset.
 */
@Component
@RequiredArgsConstructor
public class CsuConnector implements BaseConnector {

    private static final Logger log = LoggerFactory.getLogger(CsuConnector.class);
    private static final String CATALOG_BASE = "https://data.csu.gov.cz/api/katalog/v1";
    private static final String SELECTION_ENDPOINT_PREFIX = "/api/dotaz/v1/data/vybery/";
    private static final long DATASET_META_TTL_MS = Duration.ofHours(12).toMillis();

    private final ConnectorHttpSupport http;
    private final ObjectMapper objectMapper;
    private final Map<String, CachedDatasetMeta> datasetMetaCache = new ConcurrentHashMap<>();

    @Override
    public String sourceType() {
        return "csu";
    }

    @Override
    public ConnectorFetchResult fetch(Map<String, Object> source) {
        String method = string(source, "method");
        Map<String, Object> queryParams =
                source.get("query_params") instanceof Map<?, ?> m ? ConnectorHttpSupport.stringMap(m) : Map.of();
        if ("POST".equalsIgnoreCase(method) || truthy(queryParams.get("csu_full_dataset"))) {
            return fetchFullDataset(source);
        }
        return fetchSelection(source);
    }

    private ConnectorFetchResult fetchSelection(Map<String, Object> source) {
        String url = ConnectorHttpSupport.buildUrl(string(source, "base_url"), string(source, "endpoint"));
        Map<String, Object> queryParams =
                source.get("query_params") instanceof Map<?, ?> m ? ConnectorHttpSupport.stringMap(m) : Map.of();
        Map<String, Object> safeQuery = new LinkedHashMap<>(queryParams);
        safeQuery.putIfAbsent("format", "CSV");
        safeQuery.remove("csu_full_dataset");
        safeQuery.remove("csu_filters");
        try {
            HttpResponse<String> response = http.get(
                    url,
                    Map.of("Accept", "text/csv", "Accept-Language", "cs", "User-Agent", "BankIntel/1.0"),
                    safeQuery,
                    Duration.ofMinutes(3));
            if (response.statusCode() != 200) {
                // `error` nese syrové tělo od ČSÚ (kvůli zpětné kompatibilitě), ale samo o sobě
                // uživateli nic neřekne — viděl v UI holé {"error": "Interní chyba serveru"}.
                return ConnectorFetchResult.error(
                        response.statusCode(),
                        Map.of(
                                "error",
                                response.body(),
                                "detail_cs",
                                "Data ČSÚ se teď nepodařilo načíst (odpověď " + response.statusCode()
                                        + "). Zkuste to prosím znovu později."),
                        source);
            }
            return ConnectorFetchResult.ok(Map.of("csv_text", response.body()), source);
        } catch (Exception ex) {
            return ConnectorFetchResult.error(0, Map.of("error", ex.getMessage()), source);
        }
    }

    private ConnectorFetchResult fetchFullDataset(Map<String, Object> source) {
        String datasetCode = string(source, "csu_dataset_code");
        if (datasetCode.isBlank()) {
            datasetCode = extractDatasetCode(string(source, "endpoint"));
        }
        if (datasetCode.isBlank()) {
            return ConnectorFetchResult.error(400, Map.of("error", "missing csu_dataset_code"), source);
        }

        try {
            CsuDatasetMeta meta = fetchDatasetMeta(datasetCode);
            if (meta.dimCodes().isEmpty()) {
                log.warn("CSU full dataset metadata has no dimensions for {} - fallback to selection", datasetCode);
                return fetchSelection(source);
            }

            String url = ConnectorHttpSupport.buildUrl(string(source, "base_url"), string(source, "endpoint"));
            String bodyJson = objectMapper.writeValueAsString(buildDatasetPostBody(meta.dimCodes()));
            for (Integer version : postVersionAttempts(meta.version())) {
                HttpResponse<String> response = http.postJson(
                        url,
                        Map.of("Accept", "text/csv", "Accept-Language", "cs", "User-Agent", "BankIntel/1.0"),
                        queryParamsWithVersion(version),
                        bodyJson,
                        fullDatasetTimeout(source));
                if (response.statusCode() == 200 && looksLikeCsv(response.body())) {
                    return ConnectorFetchResult.ok(Map.of("csv_text", response.body()), source);
                }
                log.debug(
                        "CSU full dataset POST failed dataset={} version={} HTTP {}",
                        datasetCode,
                        version,
                        response.statusCode());
            }

            log.warn("CSU full dataset POST failed for {} - fallback to selection", datasetCode);
            return fetchSelectionFallback(source);
        } catch (Exception ex) {
            log.warn("CSU full dataset fetch failed: {} - fallback to selection", ex.getMessage());
            return fetchSelectionFallback(source);
        }
    }

    /**
     * Fallback po neúspěšném full-dataset POSTu musí jít na VÝBĚROVÝ endpoint, ne znovu na
     * datasetový.
     *
     * <p>{@code InMemorySourceBuilder#buildCsu} staví pro full-dataset režim
     * {@code endpoint = /api/dotaz/v1/data/sady/<datasetCode>/vlastni}, ale {@code set_id} nechává
     * na kódu výběru. {@link #fetchSelection} přitom URL skládá z {@code endpoint}, takže fallback
     * jen zopakoval GET na tentýž datasetový endpoint — a spadl na tomtéž.
     *
     * <p>Naměřeno na ČSÚ (WCEN04 / „Indexy cen nemovitostí - meziroční index"):
     * {@code GET /api/dotaz/v1/data/sady/WCEN04} vrací HTTP 500
     * {@code {"error": "Interní chyba serveru"}}, kdežto
     * {@code GET /api/dotaz/v1/data/vybery/WCEN04T02} vrací HTTP 200 a data. Uživateli se proto
     * zobrazila syrová hláška ČSÚ, přestože ta řada je dostupná — fallback byl neúčinný přesně
     * v situaci, kvůli které existuje.
     */
    /**
     * Jak dlouho čekat na full-dataset POST, než se přejde na výběrový endpoint.
     *
     * <p>Naměřeno na ČSÚ dataset CEN0101A: full-dataset POST doběhne až za 336 s (5 900 řádků),
     * kdežto {@code GET /vybery/CEN0101AT01} vrátí použitelná data za 0,2 s. Pro náhled v UI je
     * pětiminutové čekání k ničemu — panel se tváří zaseknutě. Náhled si proto přes
     * {@code csu_full_dataset_timeout_sec} říká o kratší strop (nastavuje
     * {@code InMemorySourceBuilder#buildCsu}); plánovaná synchronizace, která běží nad uloženým
     * zdrojem a celý dataset opravdu chce, klíč neposílá a zůstává jí původních 5 minut.
     */
    private static Duration fullDatasetTimeout(Map<String, Object> source) {
        String raw = string(source, "csu_full_dataset_timeout_sec");
        if (!raw.isBlank()) {
            try {
                int seconds = Integer.parseInt(raw.trim());
                if (seconds > 0) {
                    return Duration.ofSeconds(seconds);
                }
            } catch (NumberFormatException ignored) {
                // nečitelná hodnota - použije se výchozí strop
            }
        }
        return Duration.ofMinutes(5);
    }

    private ConnectorFetchResult fetchSelectionFallback(Map<String, Object> source) {
        String selectionCode = string(source, "csu_selection_code");
        if (selectionCode.isBlank()) {
            selectionCode = string(source, "set_id");
        }
        if (selectionCode.isBlank() || string(source, "endpoint").contains(SELECTION_ENDPOINT_PREFIX)) {
            return fetchSelection(source);
        }
        Map<String, Object> selectionSource = new LinkedHashMap<>(source);
        selectionSource.put("endpoint", SELECTION_ENDPOINT_PREFIX + selectionCode);
        selectionSource.put("method", "GET");
        selectionSource.put("query_params", Map.of("format", "CSV"));
        return fetchSelection(selectionSource);
    }

    private CsuDatasetMeta fetchDatasetMeta(String datasetCode) {
        long now = System.currentTimeMillis();
        CachedDatasetMeta cached = datasetMetaCache.get(datasetCode);
        if (cached != null && now - cached.fetchedAtMs() < DATASET_META_TTL_MS) {
            return cached.meta();
        }
        CsuDatasetMeta meta = fetchDatasetMetaUncached(datasetCode);
        datasetMetaCache.put(datasetCode, new CachedDatasetMeta(meta, now));
        return meta;
    }

    @SuppressWarnings("unchecked")
    private CsuDatasetMeta fetchDatasetMetaUncached(String datasetCode) {
        List<Map<String, Object>> rawDims = new ArrayList<>();
        Integer version = null;
        try {
            HttpResponse<String> response = http.get(
                    CATALOG_BASE + "/sady/" + datasetCode,
                    Map.of("Accept", "application/json", "Accept-Language", "cs", "User-Agent", "BankIntel/1.0"),
                    Map.of(),
                    Duration.ofSeconds(30));
            if (response.statusCode() == 200) {
                Map<String, Object> data = http.parseJson(response.body());
                version = firstInteger(data.get("verze"), data.get("verzeSady"));
                Object dims = data.get("variantyDimenze");
                if (!(dims instanceof List<?>)) {
                    dims = data.get("dimenze");
                }
                rawDims.addAll(mapList(dims));
            } else {
                log.debug("CSU dataset metadata HTTP {} for {}", response.statusCode(), datasetCode);
            }

            if (rawDims.isEmpty()) {
                HttpResponse<String> fallback = http.get(
                        CATALOG_BASE + "/sady/" + datasetCode + "/dimenze",
                        Map.of("Accept", "application/json", "Accept-Language", "cs", "User-Agent", "BankIntel/1.0"),
                        Map.of(),
                        Duration.ofSeconds(30));
                if (fallback.statusCode() == 200) {
                    Object parsed = objectMapper.readValue(fallback.body(), Object.class);
                    Object dims = parsed;
                    if (parsed instanceof Map<?, ?> parsedMap) {
                        Map<String, Object> data = ConnectorHttpSupport.stringMap(parsedMap);
                        dims = data.get("variantyDimenze");
                        if (!(dims instanceof List<?>)) {
                            dims = data.get("dimenze");
                        }
                    }
                    rawDims.addAll(mapList(dims));
                }
            }
        } catch (Exception ex) {
            log.debug("CSU dataset metadata unavailable for {}: {}", datasetCode, ex.getMessage());
        }

        return new CsuDatasetMeta(extractDimensionCodes(rawDims), version);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                out.add((Map<String, Object>) ConnectorHttpSupport.stringMap(map));
            }
        }
        return out;
    }

    private static List<String> extractDimensionCodes(List<Map<String, Object>> rawDims) {
        List<String> codes = new ArrayList<>();
        boolean seenTimeDimension = false;
        for (Map<String, Object> dim : rawDims) {
            String code = string(dim, "kod");
            if (code.isBlank() || "IndicatorType".equalsIgnoreCase(code)) {
                continue;
            }
            String type = string(dim, "typDimenzeKod");
            if ("REF_CAS".equalsIgnoreCase(type)) {
                if (seenTimeDimension) {
                    continue;
                }
                seenTimeDimension = true;
            }
            codes.add(code);
        }
        return codes;
    }

    static Map<String, Object> buildDatasetPostBody(List<String> dimCodes) {
        List<Map<String, Object>> columns = new ArrayList<>();
        columns.add(Map.of("kodDimenze", "IndicatorType", "filtr", List.of()));
        for (String code : dimCodes) {
            if (code == null || code.isBlank() || "IndicatorType".equalsIgnoreCase(code)) {
                continue;
            }
            Map<String, Object> dim = new LinkedHashMap<>();
            dim.put("kodDimenze", code);
            dim.put("filtr", List.of());
            columns.add(dim);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sloupce", columns);
        body.put("radky", List.of());
        body.put("filtryTabulky", List.of());
        return body;
    }

    @Override
    public List<Map<String, Object>> parse(Object raw, Map<String, Object> source) {
        if (!(raw instanceof Map<?, ?> map)) {
            return List.of();
        }
        Map<String, Object> payload = ConnectorHttpSupport.stringMap(map);
        String csv = string(payload, "csv_text");
        if (csv.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> rows = http.parseCsv(csv);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            out.add(normalizeCsuRow(row));
        }
        return out;
    }

    private static Map<String, Object> normalizeCsuRow(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>(row);
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String keyNorm = CatalogTextUtils.foldAscii(entry.getKey()).trim();
            if (isCsuTimeField(keyNorm) && !out.containsKey("date")) {
                out.put("date", entry.getValue());
            }
            if ("hodnota".equals(keyNorm) || "value".equals(keyNorm)) {
                try {
                    double num = Double.parseDouble(String.valueOf(entry.getValue()).replace(",", ".").replace(" ", ""));
                    out.put("value", num);
                    out.put("amount", num);
                } catch (NumberFormatException ignored) {
                    out.put("value", entry.getValue());
                }
            }
        }
        return out;
    }

    private static boolean isCsuTimeField(String keyNorm) {
        return List.of(
                        "rok",
                        "roky",
                        "obdobi",
                        "datum",
                        "date",
                        "time",
                        "period",
                        "mesic",
                        "mesice",
                        "month",
                        "months",
                        "ctvrtleti",
                        "quarter",
                        "quarters")
                .contains(keyNorm);
    }

    private static String extractDatasetCode(String endpoint) {
        if (endpoint == null) {
            return "";
        }
        int idx = endpoint.indexOf("/sady/");
        if (idx < 0) {
            return "";
        }
        String tail = endpoint.substring(idx + 6);
        int slash = tail.indexOf('/');
        return slash >= 0 ? tail.substring(0, slash) : tail;
    }

    private static List<Integer> postVersionAttempts(Integer version) {
        List<Integer> versions = new ArrayList<>();
        if (version != null) {
            versions.add(version);
        }
        if (!versions.contains(1)) {
            versions.add(1);
        }
        versions.add(null);
        return versions;
    }

    private static Map<String, Object> queryParamsWithVersion(Integer version) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("format", "CSV");
        if (version != null) {
            params.put("verzeSady", version);
        }
        return params;
    }

    private static boolean looksLikeCsv(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        String firstLine = body.split("\\R", 2)[0];
        return firstLine.contains(",") || firstLine.contains(";") || firstLine.contains("\"");
    }

    private static Integer firstInteger(Object... values) {
        for (Object value : values) {
            Integer parsed = parseInteger(value);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private static Integer parseInteger(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value).trim().toLowerCase();
        return List.of("1", "true", "yes", "on", "full").contains(text);
    }

    private record CsuDatasetMeta(List<String> dimCodes, Integer version) {}

    private record CachedDatasetMeta(CsuDatasetMeta meta, long fetchedAtMs) {}
}
