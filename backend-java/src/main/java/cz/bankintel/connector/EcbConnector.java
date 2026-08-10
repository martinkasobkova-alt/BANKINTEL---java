package cz.bankintel.connector;

import cz.bankintel.sources.ecb.EcbApiClient;
import cz.bankintel.sources.ecb.EcbAvailabilityService;
import cz.bankintel.sources.ecb.EcbCuratedCatalog;
import cz.bankintel.sources.ecb.EcbReference;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Konektor ECB — CSV z SDW (Statistical Data Warehouse).
 *
 * <p>Python originál: {@code connectors/ecb.py}. Podporuje curated {@code ecb:CZ:…} i {@code FLOW/SERIES_KEY}.
 */
@Component
@RequiredArgsConstructor
public class EcbConnector implements BaseConnector, AsyncCancellableFetch {

    private static final Set<String> ECB_INTERNAL_QUERY_KEYS = Set.of(
            "ecb_flow",
            "ecb_series_key",
            "flowRef",
            "seriesKey",
            "set_id",
            "country",
            "ecb_country",
            "ecb_indicator_id",
            "ecb_derive_yoy");

    private final ConnectorHttpSupport http;
    private final EcbCuratedCatalog catalog;
    private final EcbAvailabilityService availability;

    @Override
    public String sourceType() {
        return "ecb";
    }

    @Override
    public ConnectorFetchResult fetch(Map<String, Object> source) {
        ResolvedSeries resolved;
        try {
            resolved = resolveSeries(source);
        } catch (IllegalArgumentException ex) {
            return ConnectorFetchResult.error(400, Map.of("error", ex.getMessage(), "detail_cs", ex.getMessage()), source);
        }

        if (string(source, "base_url").isBlank()) {
            return ConnectorFetchResult.ok(Map.of("csv_text", mockCsv()), source);
        }

        Map<String, String> query = toStringQuery(source.get("query_params"));
        query.keySet().removeIf(ECB_INTERNAL_QUERY_KEYS::contains);
        query.putIfAbsent("format", "csvdata");
        query.putIfAbsent("detail", "dataonly");
        if (!query.containsKey("startPeriod")
                && !query.containsKey("endPeriod")
                && !query.containsKey("lastNObservations")
                && !query.containsKey("firstNObservations")) {
            query.put("lastNObservations", "120");
        }

        String url = EcbApiClient.buildUrl(resolved.flow(), resolved.key(), query);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "text/csv");
        headers.put("User-Agent", "banking-bi/1.0");
        mergeHeaders(headers, source.get("headers"));

        try {
            HttpResponse<String> response = http.get(url, headers, Map.of(), Duration.ofSeconds(35));
            String body = response.body() != null ? response.body() : "";
            if (response.statusCode() == 200) {
                return ConnectorFetchResult.ok(Map.of("csv_text", body), source);
            }
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", response.statusCode());
            err.put("error", body.substring(0, Math.min(body.length(), 500)));
            err.put(
                    "detail_cs",
                    EcbApiClient.ecbErrorDetailCz(resolved.flow(), resolved.key(), response.statusCode(), body));
            return ConnectorFetchResult.error(response.statusCode(), err, source);
        } catch (Exception ex) {
            return ConnectorFetchResult.error(
                    0,
                    Map.of(
                            "error", ex.getMessage(),
                            "detail_cs", "Nepodařilo se spojit s ECB API. Zkuste to prosím později."),
                    source);
        }
    }

    /** Async, genuinely cancellable counterpart of {@link #fetch}. Same resolution + response branches. */
    @Override
    public AsyncFetchHandle fetchAsync(Map<String, Object> source) {
        ResolvedSeries resolved;
        try {
            resolved = resolveSeries(source);
        } catch (IllegalArgumentException ex) {
            return AsyncFetchHandle.completed(
                    ConnectorFetchResult.error(400, Map.of("error", ex.getMessage(), "detail_cs", ex.getMessage()), source));
        }

        if (string(source, "base_url").isBlank()) {
            return AsyncFetchHandle.completed(ConnectorFetchResult.ok(Map.of("csv_text", mockCsv()), source));
        }

        Map<String, String> query = toStringQuery(source.get("query_params"));
        query.keySet().removeIf(ECB_INTERNAL_QUERY_KEYS::contains);
        query.putIfAbsent("format", "csvdata");
        query.putIfAbsent("detail", "dataonly");
        if (!query.containsKey("startPeriod")
                && !query.containsKey("endPeriod")
                && !query.containsKey("lastNObservations")
                && !query.containsKey("firstNObservations")) {
            query.put("lastNObservations", "120");
        }

        String url = EcbApiClient.buildUrl(resolved.flow(), resolved.key(), query);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "text/csv");
        headers.put("User-Agent", "banking-bi/1.0");
        mergeHeaders(headers, source.get("headers"));

        CompletableFuture<HttpResponse<String>> transportFuture =
                http.getAsync(url, headers, Map.of(), Duration.ofSeconds(35));
        CompletableFuture<ConnectorFetchResult> resultFuture = transportFuture.handle((response, ex) -> {
            if (ex != null) {
                return ConnectorFetchResult.error(
                        0,
                        Map.of(
                                "error", rootMessage(ex),
                                "detail_cs", "Nepodařilo se spojit s ECB API. Zkuste to prosím později."),
                        source);
            }
            String body = response.body() != null ? response.body() : "";
            if (response.statusCode() == 200) {
                return ConnectorFetchResult.ok(Map.of("csv_text", body), source);
            }
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", response.statusCode());
            err.put("error", body.substring(0, Math.min(body.length(), 500)));
            err.put("detail_cs", EcbApiClient.ecbErrorDetailCz(resolved.flow(), resolved.key(), response.statusCode(), body));
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
        Object csvText = payload.get("csv_text");
        if (!(csvText instanceof String csv) || csv.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> rows = ConnectorParseSupport.parseCsvPreviewRows(http.parseCsv(csv));
        if (truthyQueryParam(source, "ecb_derive_yoy")) {
            return EcbApiClient.computeYoyFromIndex(rows);
        }
        return rows;
    }

    private ResolvedSeries resolveSeries(Map<String, Object> source) {
        Map<String, Object> qp =
                source.get("query_params") instanceof Map<?, ?> m ? ConnectorHttpSupport.stringMap(m) : Map.of();
        String flow = firstNonBlank(string(source, "ecb_flow"), string(qp, "ecb_flow"), string(qp, "flowRef"));
        String key = firstNonBlank(string(source, "ecb_series_key"), string(qp, "ecb_series_key"), string(qp, "seriesKey"));
        if (!flow.isBlank() && !key.isBlank()) {
            return new ResolvedSeries(flow, key);
        }

        String setId = firstNonBlank(string(source, "set_id"), string(qp, "set_id"));
        EcbAvailabilityService.ParsedCurated curated = EcbAvailabilityService.parseCuratedSetId(setId, source, catalog);
        if (curated != null) {
            Map<String, Object> ind = catalog.indicatorById(curated.indicatorId());
            if (ind == null) {
                throw new IllegalArgumentException("Neznámý ECB ukazatel.");
            }
            EcbCuratedCatalog.SdmxKey sdmx = catalog.sdmxKeyForCountry(ind, curated.country());
            return new ResolvedSeries(sdmx.flow(), sdmx.key());
        }

        EcbReference.Parsed ref = EcbReference.parseSetId(setId);
        if (ref != null && ref.validPreviewTarget()) {
            return new ResolvedSeries(ref.flowRef(), ref.seriesKey());
        }

        String endpoint = string(source, "endpoint");
        if (endpoint.contains("/service/data/")) {
            String tail = endpoint.substring(endpoint.indexOf("/service/data/") + "/service/data/".length());
            int slash = tail.indexOf('/');
            if (slash > 0) {
                return new ResolvedSeries(tail.substring(0, slash), tail.substring(slash + 1));
            }
        }
        throw new IllegalArgumentException("ECB náhled vyžaduje flow/series key nebo kurátorovaný set_id.");
    }

    private static boolean truthyQueryParam(Map<String, Object> source, String key) {
        Map<String, Object> qp =
                source.get("query_params") instanceof Map<?, ?> m ? ConnectorHttpSupport.stringMap(m) : Map.of();
        String raw = string(qp, key);
        return "1".equals(raw) || "true".equalsIgnoreCase(raw) || "yes".equalsIgnoreCase(raw);
    }

    private static Map<String, String> toStringQuery(Object raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    out.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
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

    private static String mockCsv() {
        return "KEY,FREQ,TIME_PERIOD,OBS_VALUE\n"
                + "M.USD.EUR.SP00.A,M,2024-01,1.0916\n"
                + "M.USD.EUR.SP00.A,M,2024-02,1.0782\n";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private record ResolvedSeries(String flow, String key) {}
}
