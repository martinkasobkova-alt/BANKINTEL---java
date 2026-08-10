package cz.bankintel.connector;

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
 * Konektor ČNB ARAD — REST API {@code /aradb/api/v1/data}.
 *
 * <p>Python originál: {@code connectors/arad.py}. Vyžaduje env {@code ARAD_API_KEY}.
 */
@Component
@RequiredArgsConstructor
public class AradConnector implements BaseConnector, AsyncCancellableFetch {

    private final ConnectorHttpSupport http;

    @Override
    public String sourceType() {
        return "arad";
    }

    @Override
    public ConnectorFetchResult fetch(Map<String, Object> source) {
        String url = ConnectorHttpSupport.buildUrl(string(source, "base_url"), string(source, "endpoint"));
        Map<String, Object> queryParams =
                source.get("query_params") instanceof Map<?, ?> m ? ConnectorHttpSupport.stringMap(m) : Map.of();
        Map<String, String> headers = toHeaders(source.get("headers"));
        try {
            HttpResponse<String> response =
                    http.get(url, headers, queryParams, Duration.ofMinutes(10));
            String contentType = response.headers().firstValue("content-type").orElse("");
            String body = response.body();
            if (response.statusCode() == 200) {
                if (contentType.toLowerCase().contains("json")) {
                    Map<String, Object> json = http.parseJson(body);
                    return ConnectorFetchResult.ok(json, source);
                }
                List<Map<String, Object>> csvRows = http.parseCsv(body);
                if (!csvRows.isEmpty()) {
                    return ConnectorFetchResult.ok(Map.of("data", csvRows, "format", "csv"), source);
                }
                return ConnectorFetchResult.ok(Map.of("raw_text", body.substring(0, Math.min(body.length(), 2000))), source);
            }
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", response.statusCode());
            err.put("error", body.substring(0, Math.min(body.length(), 500)));
            return ConnectorFetchResult.error(response.statusCode(), err, source);
        } catch (Exception ex) {
            return ConnectorFetchResult.error(0, Map.of("error", ex.getMessage()), source);
        }
    }

    /**
     * Async, genuinely cancellable counterpart of {@link #fetch}. Mirrors the same response
     * handling exactly (200 + json/csv/raw-text branches, non-200 error branch), just chained onto
     * {@code getAsync(...)} instead of blocking on {@code http.get(...)}. See
     * {@link AsyncCancellableFetch} for why two futures are returned.
     */
    @Override
    public AsyncFetchHandle fetchAsync(Map<String, Object> source) {
        String url = ConnectorHttpSupport.buildUrl(string(source, "base_url"), string(source, "endpoint"));
        Map<String, Object> queryParams =
                source.get("query_params") instanceof Map<?, ?> m ? ConnectorHttpSupport.stringMap(m) : Map.of();
        Map<String, String> headers = toHeaders(source.get("headers"));
        CompletableFuture<HttpResponse<String>> transportFuture = http.getAsync(url, headers, queryParams, Duration.ofMinutes(10));
        CompletableFuture<ConnectorFetchResult> resultFuture = transportFuture.handle((response, ex) -> {
            if (ex != null) {
                return ConnectorFetchResult.error(0, Map.of("error", rootMessage(ex)), source);
            }
            try {
                String contentType = response.headers().firstValue("content-type").orElse("");
                String body = response.body();
                if (response.statusCode() == 200) {
                    if (contentType.toLowerCase().contains("json")) {
                        Map<String, Object> json = http.parseJson(body);
                        return ConnectorFetchResult.ok(json, source);
                    }
                    List<Map<String, Object>> csvRows = http.parseCsv(body);
                    if (!csvRows.isEmpty()) {
                        return ConnectorFetchResult.ok(Map.of("data", csvRows, "format", "csv"), source);
                    }
                    return ConnectorFetchResult.ok(
                            Map.of("raw_text", body.substring(0, Math.min(body.length(), 2000))), source);
                }
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("status", response.statusCode());
                err.put("error", body.substring(0, Math.min(body.length(), 500)));
                return ConnectorFetchResult.error(response.statusCode(), err, source);
            } catch (Exception parseEx) {
                return ConnectorFetchResult.error(0, Map.of("error", parseEx.getMessage()), source);
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
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> payload = ConnectorHttpSupport.stringMap(map);
            for (String key : List.of("data", "records", "rows", "items", "result")) {
                Object rows = payload.get(key);
                if (rows instanceof List<?> list) {
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> rowMap) {
                            out.add(normalizeRow(ConnectorHttpSupport.stringMap(rowMap)));
                        }
                    }
                    return out;
                }
            }
        }
        if (raw instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> rowMap) {
                    out.add(normalizeRow(ConnectorHttpSupport.stringMap(rowMap)));
                }
            }
            return out;
        }
        return List.of();
    }

    private static Map<String, Object> normalizeRow(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>(row);
        if (out.containsKey("period") && !out.containsKey("date")) {
            out.put("date", out.get("period"));
        }
        if (out.containsKey("value") && !out.containsKey("amount")) {
            try {
                out.put("amount", Double.parseDouble(String.valueOf(out.get("value")).replace(",", ".")));
            } catch (NumberFormatException ignored) {
                // keep as-is
            }
        }
        return out;
    }

    private static Map<String, String> toHeaders(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                out.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
        return out;
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
