package cz.bankintel.connector;
import cz.bankintel.util.BankIntelEnvVars;

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
 * Konektor FRED — observations API {@code api.stlouisfed.org/fred/series/observations}.
 *
 * <p>Python originál: {@code connectors/fred.py}. Vyžaduje env {@code FRED_API_KEY}.
 */
@Component
@RequiredArgsConstructor
public class FredConnector implements BaseConnector, AsyncCancellableFetch {

    private static final String FRED_API_ROOT = "https://api.stlouisfed.org/fred";
    // FRED bez explicitniho sort_order vraci vzestupne (nejstarsi prvni) - puvodni limit 1000 tak
    // pro dlouhe denni rady (napr. DGS10 ma pres 16000 pozorovani od 1962) tise oriznul VSECHNY
    // novejsi zaznamy, misto pozadovaneho "cele obdobi". Zvyseno tak, aby se realisticky vesla
    // cela historie jakekoliv rady bez orezu.
    private static final int PREVIEW_LIMIT = 50_000;
    private static final int ANALYSIS_LIMIT = 100_000;
    private static final int FRED_MAX_LIMIT = 100_000;

    private final ConnectorHttpSupport http;

    @Override
    public String sourceType() {
        return "fred";
    }

    @Override
    public ConnectorFetchResult fetch(Map<String, Object> source) {
        String seriesId = string(source, "fred_series_id");
        if (seriesId.isBlank()) {
            seriesId = string(source, "set_id");
        }
        if (seriesId.isBlank()) {
            return ConnectorFetchResult.error(
                    400,
                    Map.of(
                            "error", "missing fred_series_id",
                            "detail_cs", "V konfiguraci zdroje chybí FRED series_id."),
                    source);
        }
        String apiKey = env("FRED_API_KEY");
        if (apiKey.isBlank()) {
            return ConnectorFetchResult.error(
                    503,
                    Map.of(
                            "error", "FRED_API_KEY is missing on backend",
                            "detail_cs",
                                    "Na serveru není nastaven FRED_API_KEY — synchronizace z FRED API nelze provést."),
                    source);
        }
        try {
            Map<String, Object> sourceQuery = ConnectorHttpSupport.stringMap(source.get("query_params"));
            String recordMode = firstNonBlank(string(sourceQuery, "record_mode"), string(source, "record_mode"));
            boolean analysisMode = "analysis".equalsIgnoreCase(recordMode) || "forecast".equalsIgnoreCase(recordMode);
            int defaultLimit = analysisMode ? ANALYSIS_LIMIT : PREVIEW_LIMIT;
            int limit = boundedInt(firstNonBlank(
                    string(sourceQuery, "record_limit"),
                    string(sourceQuery, "limit"),
                    string(source, "record_limit")), defaultLimit, 1, FRED_MAX_LIMIT);

            Map<String, Object> query = new LinkedHashMap<>();
            query.put("api_key", apiKey);
            query.put("file_type", "json");
            query.put("series_id", seriesId);
            query.put("limit", String.valueOf(limit));
            if (analysisMode) {
                query.put("sort_order", "desc");
            }
            copyAllowedFredParam(sourceQuery, query, "observation_start");
            copyAllowedFredParam(sourceQuery, query, "observation_end");
            copyAllowedFredParam(sourceQuery, query, "frequency");
            copyAllowedFredParam(sourceQuery, query, "aggregation_method");
            copyAllowedFredParam(sourceQuery, query, "units");
            copyAllowedFredParam(sourceQuery, query, "output_type");
            copyAllowedFredParam(sourceQuery, query, "realtime_start");
            copyAllowedFredParam(sourceQuery, query, "realtime_end");
            String url = FRED_API_ROOT + "/series/observations";
            HttpResponse<String> response = http.get(url, Map.of(), query, Duration.ofSeconds(30));
            if (response.statusCode() != 200) {
                return ConnectorFetchResult.error(
                        response.statusCode(),
                        Map.of("error", response.body(), "detail_cs", fredDetailCs(response.statusCode())),
                        source);
            }
            Map<String, Object> data = http.parseJson(response.body());
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("fred_observations_json", data);
            raw.put("fred_series_id", seriesId);
            raw.put("mode", "fred_api_observations");
            return ConnectorFetchResult.ok(raw, source);
        } catch (Exception ex) {
            return ConnectorFetchResult.error(
                    502, Map.of("error", ex.getMessage(), "detail_cs", "Stažení dat z FRED API se nepodařilo."), source);
        }
    }

    /** Async, genuinely cancellable counterpart of {@link #fetch}. Same validation + parsing branches. */
    @Override
    public AsyncFetchHandle fetchAsync(Map<String, Object> source) {
        String seriesId = string(source, "fred_series_id");
        if (seriesId.isBlank()) {
            seriesId = string(source, "set_id");
        }
        if (seriesId.isBlank()) {
            return AsyncFetchHandle.completed(ConnectorFetchResult.error(
                    400,
                    Map.of(
                            "error", "missing fred_series_id",
                            "detail_cs", "V konfiguraci zdroje chybí FRED series_id."),
                    source));
        }
        String apiKey = env("FRED_API_KEY");
        if (apiKey.isBlank()) {
            return AsyncFetchHandle.completed(ConnectorFetchResult.error(
                    503,
                    Map.of(
                            "error", "FRED_API_KEY is missing on backend",
                            "detail_cs", "Na serveru není nastaven FRED_API_KEY — synchronizace z FRED API nelze provést."),
                    source));
        }
        Map<String, Object> sourceQuery = ConnectorHttpSupport.stringMap(source.get("query_params"));
        String recordMode = firstNonBlank(string(sourceQuery, "record_mode"), string(source, "record_mode"));
        boolean analysisMode = "analysis".equalsIgnoreCase(recordMode) || "forecast".equalsIgnoreCase(recordMode);
        int defaultLimit = analysisMode ? ANALYSIS_LIMIT : PREVIEW_LIMIT;
        int limit = boundedInt(firstNonBlank(
                string(sourceQuery, "record_limit"),
                string(sourceQuery, "limit"),
                string(source, "record_limit")), defaultLimit, 1, FRED_MAX_LIMIT);

        Map<String, Object> query = new LinkedHashMap<>();
        query.put("api_key", apiKey);
        query.put("file_type", "json");
        query.put("series_id", seriesId);
        query.put("limit", String.valueOf(limit));
        if (analysisMode) {
            query.put("sort_order", "desc");
        }
        copyAllowedFredParam(sourceQuery, query, "observation_start");
        copyAllowedFredParam(sourceQuery, query, "observation_end");
        copyAllowedFredParam(sourceQuery, query, "frequency");
        copyAllowedFredParam(sourceQuery, query, "aggregation_method");
        copyAllowedFredParam(sourceQuery, query, "units");
        copyAllowedFredParam(sourceQuery, query, "output_type");
        copyAllowedFredParam(sourceQuery, query, "realtime_start");
        copyAllowedFredParam(sourceQuery, query, "realtime_end");
        String url = FRED_API_ROOT + "/series/observations";
        String seriesIdFinal = seriesId;
        CompletableFuture<HttpResponse<String>> transportFuture = http.getAsync(url, Map.of(), query, Duration.ofSeconds(30));
        CompletableFuture<ConnectorFetchResult> resultFuture = transportFuture.handle((response, ex) -> {
            if (ex != null) {
                return ConnectorFetchResult.error(
                        502, Map.of("error", rootMessage(ex), "detail_cs", "Stažení dat z FRED API se nepodařilo."), source);
            }
            if (response.statusCode() != 200) {
                return ConnectorFetchResult.error(
                        response.statusCode(),
                        Map.of("error", response.body(), "detail_cs", fredDetailCs(response.statusCode())),
                        source);
            }
            try {
                Map<String, Object> data = http.parseJson(response.body());
                Map<String, Object> raw = new LinkedHashMap<>();
                raw.put("fred_observations_json", data);
                raw.put("fred_series_id", seriesIdFinal);
                raw.put("mode", "fred_api_observations");
                return ConnectorFetchResult.ok(raw, source);
            } catch (Exception parseEx) {
                return ConnectorFetchResult.error(
                        502, Map.of("error", parseEx.getMessage(), "detail_cs", "Stažení dat z FRED API se nepodařilo."), source);
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
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> parse(Object raw, Map<String, Object> source) {
        if (!(raw instanceof Map<?, ?> map)) {
            return List.of();
        }
        Map<String, Object> payload = ConnectorHttpSupport.stringMap(map);
        String seriesId = string(payload, "fred_series_id");
        if (seriesId.isBlank()) {
            seriesId = string(source, "fred_series_id");
        }
        Object apiBlock = payload.get("fred_observations_json");
        if (!(apiBlock instanceof Map<?, ?> apiMap)) {
            return List.of();
        }
        Object observations = ((Map<String, Object>) ConnectorHttpSupport.stringMap(apiMap)).get("observations");
        if (!(observations instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> rowMap)) {
                continue;
            }
            Map<String, Object> row = ConnectorHttpSupport.stringMap(rowMap);
            String date = string(row, "date");
            String valueRaw = string(row, "value");
            if (date.isBlank() || valueRaw.isBlank() || ".".equals(valueRaw) || "NA".equalsIgnoreCase(valueRaw)) {
                continue;
            }
            try {
                double num = Double.parseDouble(valueRaw.replace(",", "."));
                Map<String, Object> normalized = new LinkedHashMap<>();
                normalized.put("observation_date", date);
                normalized.put("date", date);
                normalized.put("value", num);
                normalized.put("amount", num);
                normalized.put("series_id", seriesId);
                normalized.put("variable", seriesId);
                normalized.put("source", "FRED");
                normalized.put("source_type", "fred");
                normalized.put("country", "US");
                out.add(normalized);
            } catch (NumberFormatException ignored) {
                // skip invalid row
            }
        }
        return out;
    }

    private static String fredDetailCs(int status) {
        return switch (status) {
            case 404 -> "FRED řada pod tímto kódem nebyla nalezena (zkontrolujte series_id).";
            case 403 -> "FRED API odmítlo dotaz (oprávnění nebo limity).";
            case 429 -> "FRED API hlásí příliš mnoho požadavků (rate limit). Zkuste to později.";
            default -> status >= 400 && status < 500
                    ? "FRED API odmítlo dotaz (špatné parametry nebo neplatná řada)."
                    : "Stažení dat z FRED API se nepodařilo.";
        };
    }

    private static String env(String name) {
        String value = BankIntelEnvVars.get(name);
        return value != null ? value.trim() : "";
    }

    private static void copyAllowedFredParam(Map<String, Object> input, Map<String, Object> query, String key) {
        String value = string(input, key);
        if (!value.isBlank()) {
            query.put(key, value);
        }
    }

    private static int boundedInt(String raw, int fallback, int min, int max) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
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
}
