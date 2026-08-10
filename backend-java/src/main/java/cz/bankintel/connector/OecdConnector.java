package cz.bankintel.connector;

import cz.bankintel.sources.oecd4.Oecd4BrowseService;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Konektor OECD — SDMX v2 JSON nebo legacy CSV (stats.oecd.org).
 *
 * <p>Python originál: {@code connectors/oecd.py}.
 */
@Component
@RequiredArgsConstructor
public class OecdConnector implements BaseConnector, AsyncCancellableFetch {

    private static final String OECD_DATA_ACCEPT = "application/vnd.sdmx.data+json; charset=utf-8; version=2";

    private final ConnectorHttpSupport http;
    private final Oecd4BrowseService oecd4BrowseService;

    @Override
    public String sourceType() {
        return "oecd";
    }

    @Override
    public ConnectorFetchResult fetch(Map<String, Object> source) {
        Map<String, Object> queryParams = buildQuery(source);
        if (useOecd4Offline(source, queryParams)) {
            try {
                List<Map<String, Object>> rows = oecd4BrowseService.previewRows(queryParams);
                return ConnectorFetchResult.ok(Map.of("rows", rows), source);
            } catch (Exception ex) {
                return ConnectorFetchResult.error(
                        0,
                        Map.of("error", ex.getMessage(), "detail_cs", "Nepodarilo se nacist lokalni OECD4 mirror."),
                        source);
            }
        }

        String url = ConnectorHttpSupport.buildUrl(string(source, "base_url"), string(source, "endpoint"));
        Map<String, String> headers = toHeaders(source);
        try {
            HttpResponse<String> response = http.get(url, headers, queryParams, Duration.ofSeconds(90));
            String body = response.body() != null ? response.body() : "";
            if (response.statusCode() == 200) {
                if (useSdmxV2(source)) {
                    try {
                        Map<String, Object> json = http.parseJson(body);
                        return ConnectorFetchResult.ok(Map.of("json", json), source);
                    } catch (Exception ex) {
                        return ConnectorFetchResult.ok(Map.of("csv_text", body), source);
                    }
                }
                return ConnectorFetchResult.ok(Map.of("csv_text", body), source);
            }
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", response.statusCode());
            err.put("error", body.substring(0, Math.min(body.length(), 500)));
            err.put("detail_cs", oecdDetailCs(response.statusCode()));
            return ConnectorFetchResult.error(response.statusCode(), err, source);
        } catch (Exception ex) {
            return ConnectorFetchResult.error(
                    0,
                    Map.of("error", ex.getMessage(), "detail_cs", "Síťová chyba při volání OECD Data API."),
                    source);
        }
    }

    /**
     * Async, genuinely cancellable counterpart of {@link #fetch}. The offline OECD4 mirror branch
     * has no network call, so it is served synchronously via {@link AsyncFetchHandle#completed}.
     */
    @Override
    public AsyncFetchHandle fetchAsync(Map<String, Object> source) {
        Map<String, Object> queryParams = buildQuery(source);
        if (useOecd4Offline(source, queryParams)) {
            try {
                List<Map<String, Object>> rows = oecd4BrowseService.previewRows(queryParams);
                return AsyncFetchHandle.completed(ConnectorFetchResult.ok(Map.of("rows", rows), source));
            } catch (Exception ex) {
                return AsyncFetchHandle.completed(ConnectorFetchResult.error(
                        0,
                        Map.of("error", ex.getMessage(), "detail_cs", "Nepodarilo se nacist lokalni OECD4 mirror."),
                        source));
            }
        }

        String url = ConnectorHttpSupport.buildUrl(string(source, "base_url"), string(source, "endpoint"));
        Map<String, String> headers = toHeaders(source);
        CompletableFuture<HttpResponse<String>> transportFuture =
                http.getAsync(url, headers, queryParams, Duration.ofSeconds(90));
        CompletableFuture<ConnectorFetchResult> resultFuture = transportFuture.handle((response, ex) -> {
            if (ex != null) {
                return ConnectorFetchResult.error(
                        0,
                        Map.of("error", rootMessage(ex), "detail_cs", "Síťová chyba při volání OECD Data API."),
                        source);
            }
            String body = response.body() != null ? response.body() : "";
            if (response.statusCode() == 200) {
                if (useSdmxV2(source)) {
                    try {
                        Map<String, Object> json = http.parseJson(body);
                        return ConnectorFetchResult.ok(Map.of("json", json), source);
                    } catch (Exception parseEx) {
                        return ConnectorFetchResult.ok(Map.of("csv_text", body), source);
                    }
                }
                return ConnectorFetchResult.ok(Map.of("csv_text", body), source);
            }
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", response.statusCode());
            err.put("error", body.substring(0, Math.min(body.length(), 500)));
            err.put("detail_cs", oecdDetailCs(response.statusCode()));
            return ConnectorFetchResult.error(response.statusCode(), err, source);
        });
        return new AsyncFetchHandle(transportFuture, resultFuture);
    }

    private static String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current == null ? "unknown error" : String.valueOf(current.getMessage());
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> parse(Object raw, Map<String, Object> source) {
        if (!(raw instanceof Map<?, ?> map)) {
            return List.of();
        }
        Map<String, Object> payload = ConnectorHttpSupport.stringMap(map);
        Object jsonObj = payload.get("json");
        if (jsonObj instanceof Map<?, ?> jsonRaw) {
            return ConnectorParseSupport.flattenOecdSdmxRecords((Map<String, Object>) ConnectorHttpSupport.stringMap(jsonRaw));
        }
        Object csvText = payload.get("csv_text");
        if (csvText instanceof String csv && !csv.isBlank()) {
            return ConnectorParseSupport.parseCsvPreviewRows(http.parseCsv(csv));
        }
        Object rowsObj = payload.get("rows");
        if (rowsObj instanceof List<?> rows) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object row : rows) {
                if (row instanceof Map<?, ?> mapRow) {
                    out.add(ConnectorHttpSupport.stringMap(mapRow));
                }
            }
            return out;
        }
        return List.of();
    }

    private static Map<String, Object> buildQuery(Map<String, Object> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        Object raw = source.get("query_params");
        if (raw instanceof Map<?, ?> map) {
            out.putAll(ConnectorHttpSupport.stringMap(map));
        }
        if (useSdmxV2(source)) {
            out.putIfAbsent("attributes", "dsd");
            out.putIfAbsent("measures", "all");
            out.putIfAbsent("dimensionAtObservation", "AllDimensions");
        } else {
            out.putIfAbsent("format", "csvfilewithlabels");
            out.putIfAbsent("dimensionAtObservation", "AllDimensions");
            out.putIfAbsent("startTime", "2010");
            out.putIfAbsent("lastNObservations", 800);
        }
        return out;
    }

    private static Map<String, String> toHeaders(Map<String, Object> source) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (useSdmxV2(source)) {
            headers.put("Accept", OECD_DATA_ACCEPT);
            headers.put("Accept-Language", "en");
            headers.put("User-Agent", "banking-bi/1.0");
        } else {
            headers.put("User-Agent", "banking-bi/1.0");
            headers.put("Accept-Language", "en");
        }
        Object raw = source.get("headers");
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    headers.putIfAbsent(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
        }
        return headers;
    }

    private static boolean useSdmxV2(Map<String, Object> source) {
        if (Boolean.TRUE.equals(source.get("oecd_sdmx_v2"))) {
            return true;
        }
        String setId = string(source, "set_id");
        if (setId.startsWith("SDMX2|")) {
            return true;
        }
        Object qpObj = source.get("query_params");
        if (qpObj instanceof Map<?, ?> qp && "sdmx_v2".equals(String.valueOf(qp.get("oecd_api_mode")).trim())) {
            return true;
        }
        String endpoint = string(source, "endpoint").replace("\\", "/");
        return endpoint.startsWith("v2/data/dataflow/");
    }

    private static boolean useOecd4Offline(Map<String, Object> source, Map<String, Object> queryParams) {
        if (Boolean.TRUE.equals(source.get("oecd4_offline"))) {
            return true;
        }
        String mode = String.valueOf(queryParams.getOrDefault("oecd_api_mode", "")).trim();
        String provider = String.valueOf(queryParams.getOrDefault("provider", "")).trim();
        return "oecd4_offline".equalsIgnoreCase(mode)
                || "oecd4".equalsIgnoreCase(provider)
                || queryParams.get("oecd4_key") != null;
    }

    private static String oecdDetailCs(int code) {
        return switch (code) {
            case 404 -> "OECD Data API nenašlo zvolený dataset, verzi nebo kombinaci dimenzí.";
            case 429 -> "OECD Data API teď omezuje počet dotazů (rate limit).";
            default -> code >= 400 && code < 500
                    ? "OECD API odmítlo dotaz — zkontrolujte agency, dataflow, verzi (+) a filter expression."
                    : "OECD Data API vrátilo serverovou chybu HTTP " + code + ".";
        };
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
