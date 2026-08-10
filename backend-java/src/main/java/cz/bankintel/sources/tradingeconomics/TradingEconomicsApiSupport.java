package cz.bankintel.sources.tradingeconomics;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Sdílený TE HTTP klient — port {@code services/tradingeconomics_catalog.py}. */
@Slf4j
@Component
public class TradingEconomicsApiSupport {

    public static final String TE_BASE_URL = "https://api.tradingeconomics.com";

    private static final List<String> COUNTRY_SNAPSHOT_GROUPS = List.of(
            "overview", "markets", "gdp", "labour", "prices", "money", "trade",
            "government", "business", "consumer", "housing", "taxes", "energy", "health", "climate");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public TradingEconomicsApiSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    }

    public String apiKey() {
        return firstNonBlank(BankIntelEnvVars.get("TRADING_ECONOMICS_API_KEY"), BankIntelEnvVars.get("TRADINGECONOMICS_API_KEY"));
    }

    public double timeoutSec() {
        String raw = BankIntelEnvVars.get("TRADINGECONOMICS_HTTP_TIMEOUT_SEC");
        if (raw == null || raw.isBlank()) {
            return 8.0;
        }
        try {
            double val = Double.parseDouble(raw.trim());
            return Math.max(3.0, Math.min(val, 20.0));
        } catch (NumberFormatException ex) {
            return 8.0;
        }
    }

    public Object getJson(String path, Map<String, String> params) throws TradingEconomicsHttpException {
        return getJson(path, params, apiKey());
    }

    public Object getJson(String path, Map<String, String> params, String apiKey) throws TradingEconomicsHttpException {
        String key = stringOrBlank(apiKey);
        if (key.isBlank()) {
            throw new IllegalArgumentException("Missing TRADING_ECONOMICS_API_KEY on backend.");
        }
        String p = "/" + stringOrBlank(path).replaceFirst("^/+", "");
        Map<String, String> query = new LinkedHashMap<>(params != null ? params : Map.of());
        query.put("c", key);
        query.putIfAbsent("f", "json");

        StringBuilder qs = new StringBuilder();
        for (Map.Entry<String, String> entry : query.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            if (!qs.isEmpty()) {
                qs.append('&');
            }
            qs.append(urlEncode(entry.getKey())).append('=').append(urlEncode(entry.getValue()));
        }
        String url = TE_BASE_URL + p + (qs.isEmpty() ? "" : "?" + qs);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis((long) (timeoutSec() * 1000)))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                String text = response.body() != null ? response.body().trim() : "";
                if (text.length() > 280) {
                    text = text.substring(0, 280);
                }
                throw new TradingEconomicsHttpException(
                        response.statusCode(), text.isBlank() ? "Trading Economics HTTP " + response.statusCode() : text);
            }
            String body = response.body() != null ? response.body() : "";
            return objectMapper.readValue(body, new TypeReference<>() {});
        } catch (TradingEconomicsHttpException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new TradingEconomicsHttpException(502, ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new TradingEconomicsHttpException(502, "Trading Economics request interrupted.");
        }
    }

    public List<Map<String, Object>> getJsonList(String path, Map<String, String> params)
            throws TradingEconomicsHttpException {
        Object raw = getJson(path, params);
        return asMapList(raw);
    }

    public List<Map<String, Object>> countrySnapshotRows(String country) throws TradingEconomicsHttpException {
        String c = stringOrBlank(country);
        if (c.isBlank()) {
            return List.of();
        }
        String countryPath = urlEncode(c.toLowerCase(Locale.ROOT)).replace("+", "%20");
        List<Map<String, String>> paramBatches = new ArrayList<>();
        paramBatches.add(Map.of("f", "json"));
        for (String group : COUNTRY_SNAPSHOT_GROUPS) {
            paramBatches.add(Map.of("f", "json", "group", group));
        }

        List<Map<String, Object>> merged = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        TradingEconomicsHttpException firstBatchError = null;

        for (int idx = 0; idx < paramBatches.size(); idx++) {
            try {
                List<Map<String, Object>> rows = getJsonList("/country/" + countryPath, paramBatches.get(idx));
                for (Map<String, Object> row : rows) {
                    String rid = countrySnapshotRowIdentity(row);
                    if (rid.isBlank() || seen.contains(rid)) {
                        continue;
                    }
                    seen.add(rid);
                    merged.add(row);
                }
            } catch (TradingEconomicsHttpException ex) {
                if (ex.statusCode() == 401 || ex.statusCode() == 403) {
                    throw ex;
                }
                if (idx == 0) {
                    firstBatchError = ex;
                }
            }
        }
        if (merged.isEmpty() && firstBatchError != null) {
            throw firstBatchError;
        }
        return merged;
    }

    public Object getCached(String cacheKey, long ttlSec, Fetcher fetcher) throws TradingEconomicsHttpException {
        long now = System.nanoTime();
        CacheEntry entry = cache.get(cacheKey);
        if (entry != null && now < entry.expiresAtNs) {
            return entry.value;
        }
        Object value = fetcher.fetch();
        cache.put(cacheKey, new CacheEntry(now + ttlSec * 1_000_000_000L, value));
        return value;
    }

    public void putCache(String cacheKey, long ttlSec, Object value) {
        cache.put(cacheKey, new CacheEntry(System.nanoTime() + ttlSec * 1_000_000_000L, value));
    }

    public static String countrySnapshotRowIdentity(Map<String, Object> row) {
        String sym = ciGet(row, "HistoricalDataSymbol", "historicaldatasymbol");
        if (!sym.isBlank()) {
            return "sym:" + sym.toUpperCase(Locale.ROOT);
        }
        String url = ciGet(row, "URL", "Url").toLowerCase(Locale.ROOT);
        if (!url.isBlank()) {
            return "url:" + url;
        }
        return "cat:" + ciGet(row, "Category", "category").toLowerCase(Locale.ROOT)
                + "|f:" + ciGet(row, "Frequency").toLowerCase(Locale.ROOT)
                + "|u:" + ciGet(row, "Unit").toLowerCase(Locale.ROOT)
                + "|a:" + ciGet(row, "Adjustment").toLowerCase(Locale.ROOT)
                + "|t:" + ciGet(row, "Title").toLowerCase(Locale.ROOT);
    }

    public static String ciGet(Map<String, Object> row, String... keys) {
        Map<String, Object> lower = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            lower.put(entry.getKey().trim().toLowerCase(Locale.ROOT), entry.getValue());
        }
        for (String key : keys) {
            Object val = lower.get(key.trim().toLowerCase(Locale.ROOT));
            if (val != null && !String.valueOf(val).isBlank()) {
                return String.valueOf(val).trim();
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> asMapList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() != null) {
                        row.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
                out.add(row);
            }
        }
        return out;
    }

    public static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public static String countrySlug(String country) {
        StringBuilder sb = new StringBuilder();
        for (char ch : stringOrBlank(country).toCharArray()) {
            sb.append(Character.isLetterOrDigit(ch) ? Character.toLowerCase(ch) : '-');
        }
        String s = sb.toString().replaceAll("-+", "-").replaceAll("^-|-$", "");
        return s;
    }

    public static String indicatorSlug(String raw) {
        String s = stringOrBlank(raw).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (s.length() > 90) {
            s = s.substring(0, 90);
        }
        return s.isBlank() ? "indicator" : s;
    }

    private static String stringOrBlank(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    @FunctionalInterface
    public interface Fetcher {
        Object fetch() throws TradingEconomicsHttpException;
    }

    private record CacheEntry(long expiresAtNs, Object value) {}

    public static class TradingEconomicsHttpException extends RuntimeException {
        private final int statusCode;

        public TradingEconomicsHttpException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return statusCode;
        }
    }
}
