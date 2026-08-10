package cz.bankintel.connector;
import cz.bankintel.util.BankIntelEnvVars;

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
 * Konektor IMF — SDMX 3.0 JSON API.
 *
 * <p>Python originál: {@code connectors/imf.py}. Volitelně env {@code IMF_API_KEY}.
 */
@Component
@RequiredArgsConstructor
public class ImfConnector implements BaseConnector, AsyncCancellableFetch {

    private static final String IMF_ACCEPT = "application/vnd.sdmx.data+json;version=2.0.0";

    private final ConnectorHttpSupport http;

    @Override
    public String sourceType() {
        return "imf";
    }

    @Override
    public ConnectorFetchResult fetch(Map<String, Object> source) {
        String apiKey = env("IMF_API_KEY");
        if (apiKey.isBlank()) {
            return ConnectorFetchResult.error(
                    503,
                    Map.of(
                            "error", "imf_api_key_missing",
                            "detail_cs", "IMF_API_KEY není nastaven v prostředí serveru."),
                    source);
        }

        String url = ConnectorHttpSupport.buildUrl(string(source, "base_url"), string(source, "endpoint"));
        Map<String, Object> queryParams =
                source.get("query_params") instanceof Map<?, ?> m ? ConnectorHttpSupport.stringMap(m) : Map.of();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", IMF_ACCEPT);
        headers.put("User-Agent", "bankintel-bi/1.0");
        headers.put("Ocp-Apim-Subscription-Key", apiKey);
        mergeHeaders(headers, source.get("headers"));

        try {
            HttpResponse<String> response = http.get(url, headers, queryParams, Duration.ofSeconds(60));
            String body = response.body() != null ? response.body() : "";
            if (response.statusCode() == 200) {
                Map<String, Object> json = http.parseJson(body);
                Map<String, Object> raw = new LinkedHashMap<>();
                raw.put("json", json);
                raw.put("imf_sdmx3", true);
                return ConnectorFetchResult.ok(raw, source);
            }
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", response.statusCode());
            err.put("error", body.substring(0, Math.min(body.length(), 500)));
            err.put("imf_sdmx3", true);
            err.put("detail_cs", imfDetailCs(response.statusCode()));
            return ConnectorFetchResult.error(response.statusCode(), err, source);
        } catch (Exception ex) {
            return ConnectorFetchResult.error(
                    0,
                    Map.of(
                            "error", ex.getMessage(),
                            "detail_cs", "IMF Data API není dostupné (síť nebo timeout).",
                            "imf_sdmx3", true),
                    source);
        }
    }

    /** Async, genuinely cancellable counterpart of {@link #fetch}. Same validation + response branches. */
    @Override
    public AsyncFetchHandle fetchAsync(Map<String, Object> source) {
        String apiKey = env("IMF_API_KEY");
        if (apiKey.isBlank()) {
            return AsyncFetchHandle.completed(ConnectorFetchResult.error(
                    503,
                    Map.of(
                            "error", "imf_api_key_missing",
                            "detail_cs", "IMF_API_KEY není nastaven v prostředí serveru."),
                    source));
        }

        String url = ConnectorHttpSupport.buildUrl(string(source, "base_url"), string(source, "endpoint"));
        Map<String, Object> queryParams =
                source.get("query_params") instanceof Map<?, ?> m ? ConnectorHttpSupport.stringMap(m) : Map.of();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", IMF_ACCEPT);
        headers.put("User-Agent", "bankintel-bi/1.0");
        headers.put("Ocp-Apim-Subscription-Key", apiKey);
        mergeHeaders(headers, source.get("headers"));

        CompletableFuture<HttpResponse<String>> transportFuture =
                http.getAsync(url, headers, queryParams, Duration.ofSeconds(60));
        CompletableFuture<ConnectorFetchResult> resultFuture = transportFuture.handle((response, ex) -> {
            if (ex != null) {
                return ConnectorFetchResult.error(
                        0,
                        Map.of(
                                "error", rootMessage(ex),
                                "detail_cs", "IMF Data API není dostupné (síť nebo timeout).",
                                "imf_sdmx3", true),
                        source);
            }
            String body = response.body() != null ? response.body() : "";
            if (response.statusCode() == 200) {
                try {
                    Map<String, Object> json = http.parseJson(body);
                    Map<String, Object> raw = new LinkedHashMap<>();
                    raw.put("json", json);
                    raw.put("imf_sdmx3", true);
                    return ConnectorFetchResult.ok(raw, source);
                } catch (Exception parseEx) {
                    return ConnectorFetchResult.error(
                            0, Map.of("error", parseEx.getMessage(), "imf_sdmx3", true), source);
                }
            }
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", response.statusCode());
            err.put("error", body.substring(0, Math.min(body.length(), 500)));
            err.put("imf_sdmx3", true);
            err.put("detail_cs", imfDetailCs(response.statusCode()));
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
        if (!(jsonObj instanceof Map<?, ?> jsonRaw)) {
            return List.of();
        }
        return ConnectorParseSupport.parseImfSdmxDataJson((Map<String, Object>) ConnectorHttpSupport.stringMap(jsonRaw));
    }

    private static String imfDetailCs(int code) {
        if (code == 429 || code == 502 || code == 503 || code == 504 || code >= 500) {
            return "IMF Data API není dostupné. Zkuste to prosím znovu později.";
        }
        if (code >= 400 && code < 500) {
            return "IMF Data API odmítlo dotaz. Zkontrolujte dataflow, řadu a období.";
        }
        return "upstream HTTP " + code;
    }

    private static void mergeHeaders(Map<String, String> headers, Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                headers.putIfAbsent(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
    }

    private static String env(String name) {
        String value = BankIntelEnvVars.get(name);
        return value != null ? value.trim() : "";
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
