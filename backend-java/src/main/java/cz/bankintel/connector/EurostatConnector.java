package cz.bankintel.connector;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Konektor Eurostat — JSON-stat API (veřejné, bez klíče).
 *
 * <p>Python originál: {@code connectors/eurostat.py} (zjednodušená verze bez preview planneru).
 */
@Component
@RequiredArgsConstructor
public class EurostatConnector implements BaseConnector, AsyncCancellableFetch {

    private static final int MAX_ROWS = 250_000;
    private static final int PREVIEW_ROW_LIMIT = 1000;

    private final ConnectorHttpSupport http;

    @Override
    public String sourceType() {
        return "eurostat";
    }

    @Override
    public ConnectorFetchResult fetch(Map<String, Object> source) {
        String url = ConnectorHttpSupport.buildUrl(string(source, "base_url"), string(source, "endpoint"));
        Map<String, Object> queryParams = normalizeParams(source);
        Map<String, String> headers = toHeaders(source.get("headers"));
        try {
            HttpResponse<String> response = http.get(url, headers, queryParams, Duration.ofSeconds(120));
            if (response.statusCode() != 200) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("status", response.statusCode());
                err.put("error", response.body() != null ? response.body().substring(0, Math.min(response.body().length(), 500)) : "");
                err.put("detail_cs", "Eurostat API vrátilo HTTP " + response.statusCode());
                return ConnectorFetchResult.error(response.statusCode(), err, source);
            }
            Map<String, Object> json = http.parseJson(response.body());
            return ConnectorFetchResult.ok(json, source);
        } catch (Exception ex) {
            return ConnectorFetchResult.error(0, Map.of("error", ex.getMessage(), "detail_cs", ex.getMessage()), source);
        }
    }

    /** Async, genuinely cancellable counterpart of {@link #fetch}. Same response handling. */
    @Override
    public AsyncFetchHandle fetchAsync(Map<String, Object> source) {
        String url = ConnectorHttpSupport.buildUrl(string(source, "base_url"), string(source, "endpoint"));
        Map<String, Object> queryParams = normalizeParams(source);
        Map<String, String> headers = toHeaders(source.get("headers"));
        CompletableFuture<HttpResponse<String>> transportFuture =
                http.getAsync(url, headers, queryParams, Duration.ofSeconds(120));
        CompletableFuture<ConnectorFetchResult> resultFuture = transportFuture.handle((response, ex) -> {
            if (ex != null) {
                return ConnectorFetchResult.error(0, Map.of("error", rootMessage(ex), "detail_cs", rootMessage(ex)), source);
            }
            if (response.statusCode() != 200) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("status", response.statusCode());
                err.put(
                        "error",
                        response.body() != null ? response.body().substring(0, Math.min(response.body().length(), 500)) : "");
                err.put("detail_cs", "Eurostat API vrátilo HTTP " + response.statusCode());
                return ConnectorFetchResult.error(response.statusCode(), err, source);
            }
            try {
                Map<String, Object> json = http.parseJson(response.body());
                return ConnectorFetchResult.ok(json, source);
            } catch (Exception parseEx) {
                return ConnectorFetchResult.error(0, Map.of("error", parseEx.getMessage(), "detail_cs", parseEx.getMessage()), source);
            }
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
    public List<Map<String, Object>> parse(Object raw, Map<String, Object> source) {
        if (!(raw instanceof Map<?, ?> map)) {
            return List.of();
        }
        return EurostatJsonStatParser.flatten(ConnectorHttpSupport.stringMap(map), Math.min(MAX_ROWS, PREVIEW_ROW_LIMIT * 50));
    }

    private static Map<String, Object> normalizeParams(Map<String, Object> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        Object refAreaFallback = null;
        Object raw = source.get("query_params");
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                String key = String.valueOf(entry.getKey()).trim();
                String low = key.toLowerCase();
                // Eurostat JSON-stat API zná jen `geo`; REF_AREA (SDMX geo dimenze z ECB/Data360
                // configů nebo staršího uloženého widgetu) vrací HTTP 400 „Dimension REF_AREA is not
                // defined". Jeho hodnotu použijeme jako fallback pro `geo` a klíč zahodíme, aby se do
                // dotazu na Eurostat nikdy nedostal.
                if ("ref_area".equals(low)) {
                    refAreaFallback = entry.getValue();
                    continue;
                }
                if (key.isBlank()
                        || key.startsWith("_")
                        || low.endsWith("_label")
                        || "query_mode".equals(low)
                        || "geo_scope".equals(low)) {
                    continue;
                }
                out.put(key, entry.getValue());
            }
        }
        if (refAreaFallback != null) {
            out.putIfAbsent("geo", refAreaFallback);
        }
        out.putIfAbsent("format", "JSON");
        out.putIfAbsent("lang", "EN");
        return out;
    }

    private static Map<String, String> toHeaders(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of("Accept", "application/json");
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                out.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
        if (!out.containsKey("Accept")) {
            out.put("Accept", "application/json");
        }
        return out;
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
