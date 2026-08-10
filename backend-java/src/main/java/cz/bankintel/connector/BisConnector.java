package cz.bankintel.connector;

import cz.bankintel.sources.bis.BisCatalogService;
import cz.bankintel.sources.bis.BisCatalogService.ParsedSetId;
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
 * Konektor BIS Statistics — SDMX Generic Data (XML/CSV).
 *
 * <p>Python originál: {@code connectors/bis.py}. Set ID: {@code BIS|flow|key} nebo {@code flow/key}.
 */
@Component
@RequiredArgsConstructor
public class BisConnector implements BaseConnector, AsyncCancellableFetch {

    private final ConnectorHttpSupport http;

    @Override
    public String sourceType() {
        return "bis";
    }

    @Override
    public ConnectorFetchResult fetch(Map<String, Object> source) {
        ParsedSetId parsed;
        try {
            parsed = resolveSetId(source);
        } catch (IllegalArgumentException ex) {
            return ConnectorFetchResult.error(400, Map.of("error", ex.getMessage(), "detail_cs", ex.getMessage()), source);
        }

        Map<String, String> query = toStringQuery(normalizeQuery(source));
        String url = BisCatalogService.buildBisDataUrl(parsed.flow(), parsed.key(), query);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", BisCatalogService.BIS_GENERICDATA_ACCEPT);
        headers.put("User-Agent", "banking-bi/1.0");
        mergeHeaders(headers, source.get("headers"));

        try {
            HttpResponse<String> response = http.get(url, headers, Map.of(), Duration.ofSeconds(90));
            String body = response.body() != null ? response.body() : "";
            String contentType = response.headers().firstValue("content-type").orElse("").toLowerCase();
            if (response.statusCode() == 200) {
                String trimmed = body.stripLeading();
                if (contentType.contains("xml") || trimmed.startsWith("<?xml") || trimmed.startsWith("<")) {
                    return ConnectorFetchResult.ok(Map.of("xml_text", body), source);
                }
                return ConnectorFetchResult.ok(Map.of("csv_text", body), source);
            }
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", response.statusCode());
            err.put("error", body.substring(0, Math.min(body.length(), 500)));
            err.put("detail_cs", bisDetailCs(response.statusCode()));
            return ConnectorFetchResult.error(response.statusCode(), err, source);
        } catch (Exception ex) {
            return ConnectorFetchResult.error(
                    0,
                    Map.of("error", ex.getMessage(), "detail_cs", "BIS API je dočasně nedostupné."),
                    source);
        }
    }

    /** Async, genuinely cancellable counterpart of {@link #fetch}. Same resolution + response branches. */
    @Override
    public AsyncFetchHandle fetchAsync(Map<String, Object> source) {
        ParsedSetId parsed;
        try {
            parsed = resolveSetId(source);
        } catch (IllegalArgumentException ex) {
            return AsyncFetchHandle.completed(
                    ConnectorFetchResult.error(400, Map.of("error", ex.getMessage(), "detail_cs", ex.getMessage()), source));
        }

        Map<String, String> query = toStringQuery(normalizeQuery(source));
        String url = BisCatalogService.buildBisDataUrl(parsed.flow(), parsed.key(), query);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", BisCatalogService.BIS_GENERICDATA_ACCEPT);
        headers.put("User-Agent", "banking-bi/1.0");
        mergeHeaders(headers, source.get("headers"));

        CompletableFuture<HttpResponse<String>> transportFuture =
                http.getAsync(url, headers, Map.of(), Duration.ofSeconds(90));
        CompletableFuture<ConnectorFetchResult> resultFuture = transportFuture.handle((response, ex) -> {
            if (ex != null) {
                return ConnectorFetchResult.error(
                        0, Map.of("error", rootMessage(ex), "detail_cs", "BIS API je dočasně nedostupné."), source);
            }
            String body = response.body() != null ? response.body() : "";
            String contentType = response.headers().firstValue("content-type").orElse("").toLowerCase();
            if (response.statusCode() == 200) {
                String trimmed = body.stripLeading();
                if (contentType.contains("xml") || trimmed.startsWith("<?xml") || trimmed.startsWith("<")) {
                    return ConnectorFetchResult.ok(Map.of("xml_text", body), source);
                }
                return ConnectorFetchResult.ok(Map.of("csv_text", body), source);
            }
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", response.statusCode());
            err.put("error", body.substring(0, Math.min(body.length(), 500)));
            err.put("detail_cs", bisDetailCs(response.statusCode()));
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
    public List<Map<String, Object>> parse(Object raw, Map<String, Object> source) {
        if (!(raw instanceof Map<?, ?> map)) {
            return List.of();
        }
        Map<String, Object> payload = ConnectorHttpSupport.stringMap(map);
        Object xmlText = payload.get("xml_text");
        if (xmlText instanceof String xml && !xml.isBlank()) {
            return ConnectorParseSupport.parseBisGenericDataXml(xml);
        }
        Object csvText = payload.get("csv_text");
        if (csvText instanceof String csv && !csv.isBlank()) {
            return ConnectorParseSupport.parseCsvPreviewRows(http.parseCsv(csv));
        }
        return List.of();
    }

    private static ParsedSetId resolveSetId(Map<String, Object> source) {
        String catalogSetId = string(source, "bis_catalog_set_id");
        if (!catalogSetId.isBlank()) {
            return BisCatalogService.parseSetId(catalogSetId);
        }
        String flow = string(source, "bis_dataflow");
        String key = string(source, "bis_series_key");
        if (!flow.isBlank() && !key.isBlank()) {
            return new ParsedSetId(flow, key);
        }
        Map<String, Object> qp = source.get("query_params") instanceof Map<?, ?> m ? ConnectorHttpSupport.stringMap(m) : Map.of();
        String qpFlow = string(qp, "flow");
        String qpKey = string(qp, "key");
        if (!qpFlow.isBlank() && !qpKey.isBlank()) {
            return new ParsedSetId(qpFlow, qpKey);
        }
        return BisCatalogService.parseSetId(string(source, "set_id"));
    }

    private static Map<String, Object> normalizeQuery(Map<String, Object> source) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query_params", source.get("query_params"));
        String qctx = string(source, "_bis_query_context");
        if (qctx.isBlank()) {
            qctx = "preview";
        }
        return BisCatalogService.normalizeBisQueryParams(payload, qctx);
    }

    private static Map<String, String> toStringQuery(Map<String, Object> query) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : query.entrySet()) {
            if (entry.getValue() != null && !String.valueOf(entry.getValue()).isBlank()) {
                out.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }
        return out;
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

    private static String bisDetailCs(int code) {
        return switch (code) {
            case 404 -> "BIS dataflow nebo key nebyl nalezen.";
            case 406 -> "BIS API neakceptovalo požadovaný formát odpovědi.";
            case 503 -> "BIS API je dočasně nedostupné.";
            default -> code >= 400 && code < 500
                    ? "BIS Stats API odmítlo dotaz. Zkontrolujte flow, key a formát období."
                    : "upstream HTTP " + code;
        };
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
