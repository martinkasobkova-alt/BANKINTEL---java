package cz.bankintel.controller.sources;

import cz.bankintel.sources.tradingeconomics.TradingEconomicsApiSupport;
import cz.bankintel.sources.tradingeconomics.TradingEconomicsApiSupport.TradingEconomicsHttpException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Trading Economics REST proxy ({@code /api/trading-economics/*}). Port {@code trading_economics_routes.py}. */
@RestController
@RequestMapping("/api/trading-economics")
@RequiredArgsConstructor
public class TradingEconomicsController {

    private static final String SOURCE = "trading_economics";
    private static final long TTL_DAY = 24 * 60 * 60;
    private static final long TTL_COUNTRY_SNAPSHOT = 12 * 60 * 60;

    private final TradingEconomicsApiSupport teApi;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry> countryCache = new ConcurrentHashMap<>();

    @GetMapping("/countries")
    public ResponseEntity<Map<String, Object>> countries() {
        if (teApi.apiKey().isBlank()) {
            return asResponse(missingKeyPayload(), HttpStatus.SERVICE_UNAVAILABLE);
        }
        Object cached = cacheGet("countries", TTL_DAY);
        if (cached instanceof List<?> list) {
            return asResponse(okPayload(Map.of("countries", list)), HttpStatus.OK);
        }
        try {
            List<Map<String, Object>> rows = teApi.getJsonList("/country", Map.of());
            List<Map<String, Object>> countries = new ArrayList<>();
            Set<String> seen = new java.util.LinkedHashSet<>();
            for (Map<String, Object> row : rows) {
                String country = textOrNull(TradingEconomicsApiSupport.ciGet(row, "Country", "Name", "country"));
                if (country == null) {
                    continue;
                }
                String lk = country.toLowerCase(Locale.ROOT);
                if (seen.contains(lk)) {
                    continue;
                }
                seen.add(lk);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("country", country);
                item.put("continent", textOrNull(TradingEconomicsApiSupport.ciGet(row, "Continent")));
                item.put("group", textOrNull(TradingEconomicsApiSupport.ciGet(row, "Group", "CountryGroup")));
                item.put("iso3", textOrNull(TradingEconomicsApiSupport.ciGet(row, "ISO3", "Iso3")));
                item.put("iso2", textOrNull(TradingEconomicsApiSupport.ciGet(row, "ISO2", "CountryCode", "Iso2")));
                countries.add(item);
            }
            countries.sort(Comparator.comparing(v -> String.valueOf(v.get("country")).toLowerCase(Locale.ROOT)));
            cachePut("countries", countries);
            return asResponse(okPayload(Map.of("countries", countries)), HttpStatus.OK);
        } catch (TradingEconomicsHttpException ex) {
            var handled = handleTeError(ex);
            return asResponse(handled.payload(), HttpStatus.valueOf(handled.status()));
        }
    }

    @GetMapping("/indicators")
    public ResponseEntity<Map<String, Object>> indicators() {
        if (teApi.apiKey().isBlank()) {
            return asResponse(missingKeyPayload(), HttpStatus.SERVICE_UNAVAILABLE);
        }
        Object cached = cacheGet("indicators", TTL_DAY);
        if (cached instanceof List<?> list) {
            return asResponse(okPayload(Map.of("indicators", list)), HttpStatus.OK);
        }
        try {
            List<Map<String, Object>> rows = teApi.getJsonList("/indicators", Map.of());
            List<Map<String, Object>> out = new ArrayList<>();
            Set<String> seen = new java.util.LinkedHashSet<>();
            for (Map<String, Object> row : rows) {
                String category = textOrNull(TradingEconomicsApiSupport.ciGet(row, "Category", "Indicator", "Title"));
                if (category == null) {
                    continue;
                }
                String categoryGroup = textOrNull(TradingEconomicsApiSupport.ciGet(row, "CategoryGroup", "Group"));
                String sig = category.toLowerCase(Locale.ROOT) + "|" + String.valueOf(categoryGroup).toLowerCase(Locale.ROOT);
                if (seen.contains(sig)) {
                    continue;
                }
                seen.add(sig);
                out.add(Map.of("category", category, "category_group", categoryGroup));
            }
            out.sort(Comparator.comparing((Map<String, Object> x) -> String.valueOf(x.get("category_group")).toLowerCase(Locale.ROOT))
                    .thenComparing(x -> String.valueOf(x.get("category")).toLowerCase(Locale.ROOT)));
            cachePut("indicators", out);
            return asResponse(okPayload(Map.of("indicators", out)), HttpStatus.OK);
        } catch (TradingEconomicsHttpException ex) {
            var handled = handleTeError(ex);
            return asResponse(handled.payload(), HttpStatus.valueOf(handled.status()));
        }
    }

    @GetMapping("/country/{country}/indicators")
    public ResponseEntity<Map<String, Object>> countryIndicators(@PathVariable String country) {
        if (teApi.apiKey().isBlank()) {
            return asResponse(missingKeyPayload(), HttpStatus.SERVICE_UNAVAILABLE);
        }
        String countryName = normalizeCountryName(country);
        if (countryName.isBlank()) {
            return asResponse(errPayload("invalid_request", "Country is required."), HttpStatus.BAD_REQUEST);
        }
        String cacheKey = "merged1:" + countryName.toLowerCase(Locale.ROOT);
        Object cached = countryCacheGet(cacheKey);
        if (cached instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) map;
            return asResponse(payload, HttpStatus.OK);
        }
        try {
            List<Map<String, Object>> rows = teApi.countrySnapshotRows(countryName);
            List<Map<String, Object>> indicators = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                String category = textOrNull(TradingEconomicsApiSupport.ciGet(row, "Category", "Indicator", "Title"));
                if (category == null) {
                    continue;
                }
                Map<String, Object> normalized = new LinkedHashMap<>();
                normalized.put("category", category);
                normalized.put("title", textOrNull(TradingEconomicsApiSupport.ciGet(row, "Title")));
                normalized.put("category_group", textOrNull(TradingEconomicsApiSupport.ciGet(row, "CategoryGroup", "Group")));
                normalized.put("historical_data_symbol", textOrNull(TradingEconomicsApiSupport.ciGet(row, "HistoricalDataSymbol")));
                normalized.put("latest_value", toFloatOrNull(firstPresent(row, "LatestValue", "Value", "Close")));
                normalized.put("unit", textOrNull(TradingEconomicsApiSupport.ciGet(row, "Unit")));
                normalized.put("frequency", textOrNull(TradingEconomicsApiSupport.ciGet(row, "Frequency")));
                normalized.put("last_update", textOrNull(TradingEconomicsApiSupport.ciGet(row, "DateTime", "Date", "LatestValueDate", "LastUpdate")));
                normalized.put("source_url", textOrNull(TradingEconomicsApiSupport.ciGet(row, "URL", "SourceURL", "SourceUrl")));
                indicators.add(normalized);
            }
            indicators.sort(Comparator
                    .comparing((Map<String, Object> x) -> String.valueOf(x.get("category_group")).toLowerCase(Locale.ROOT))
                    .thenComparing(x -> String.valueOf(x.get("category")).toLowerCase(Locale.ROOT))
                    .thenComparing(x -> String.valueOf(x.get("historical_data_symbol")).toLowerCase(Locale.ROOT)));
            Map<String, Object> payload = okPayload(Map.of("country", countryName, "indicators", indicators));
            countryCachePut(cacheKey, payload);
            return asResponse(payload, HttpStatus.OK);
        } catch (TradingEconomicsHttpException ex) {
            var handled = handleTeError(ex);
            return asResponse(handled.payload(), HttpStatus.valueOf(handled.status()));
        }
    }

    @GetMapping("/calendar-indicators")
    public ResponseEntity<Map<String, Object>> calendarIndicators(@RequestParam("country") String country) {
        if (teApi.apiKey().isBlank()) {
            return asResponse(missingKeyPayload(), HttpStatus.SERVICE_UNAVAILABLE);
        }
        String countryName = normalizeCountryName(country);
        if (countryName.isBlank()) {
            return asResponse(errPayload("invalid_request", "Country is required."), HttpStatus.BAD_REQUEST);
        }
        try {
            List<Map<String, Object>> rows = teApi.getJsonList("/indicators", Map.of("calendar", "1", "country", countryName));
            List<Map<String, Object>> indicators = new ArrayList<>();
            Set<String> seen = new java.util.LinkedHashSet<>();
            for (Map<String, Object> row : rows) {
                String category = textOrNull(TradingEconomicsApiSupport.ciGet(row, "Category", "Indicator", "Title"));
                if (category == null) {
                    continue;
                }
                String categoryGroup = textOrNull(TradingEconomicsApiSupport.ciGet(row, "CategoryGroup", "Group"));
                String sig = category.toLowerCase(Locale.ROOT) + "|" + String.valueOf(categoryGroup).toLowerCase(Locale.ROOT);
                if (seen.contains(sig)) {
                    continue;
                }
                seen.add(sig);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("category", category);
                item.put("category_group", categoryGroup);
                item.put("raw", row);
                indicators.add(item);
            }
            return asResponse(okPayload(Map.of("country", countryName, "indicators", indicators)), HttpStatus.OK);
        } catch (TradingEconomicsHttpException ex) {
            var handled = handleTeError(ex);
            return asResponse(handled.payload(), HttpStatus.valueOf(handled.status()));
        }
    }

    @GetMapping("/historical")
    public ResponseEntity<Map<String, Object>> historical(
            @RequestParam("country") String country,
            @RequestParam("indicator") String indicator,
            @RequestParam(value = "from", required = false) String fromDate,
            @RequestParam(value = "to", required = false) String toDate) {
        if (teApi.apiKey().isBlank()) {
            return asResponse(missingKeyPayload(), HttpStatus.SERVICE_UNAVAILABLE);
        }
        String countryName = normalizeCountryName(country);
        String indicatorName = textOrNull(indicator);
        if (countryName.isBlank() || indicatorName == null) {
            return asResponse(errPayload("invalid_request", "Both country and indicator are required."), HttpStatus.BAD_REQUEST);
        }
        LocalDate start;
        LocalDate end;
        try {
            start = fromDate != null && !fromDate.isBlank() ? LocalDate.parse(fromDate) : LocalDate.now().minusYears(10);
            end = toDate != null && !toDate.isBlank() ? LocalDate.parse(toDate) : LocalDate.now();
            if (start.isAfter(end)) {
                throw new IllegalArgumentException("Invalid date range: from must be <= to.");
            }
        } catch (Exception ex) {
            return asResponse(errPayload("invalid_request", ex.getMessage()), HttpStatus.BAD_REQUEST);
        }

        Map<String, String> params = Map.of("initDate", start.toString(), "endDate", end.toString());
        String path = "/historical/country/"
                + TradingEconomicsApiSupport.urlEncode(countryName).replace("+", "%20")
                + "/indicator/"
                + TradingEconomicsApiSupport.urlEncode(indicatorName).replace("+", "%20");
        try {
            List<Map<String, Object>> rows = teApi.getJsonList(path, params);
            List<Map<String, Object>> data = new ArrayList<>();
            String unit = null;
            String frequency = null;
            for (Map<String, Object> row : rows) {
                String dt = safeIsoDate(TradingEconomicsApiSupport.ciGet(row, "DateTime", "Date", "LastUpdate", "LatestValueDate"));
                if (dt == null) {
                    continue;
                }
                LocalDate dtObj;
                try {
                    dtObj = LocalDate.parse(dt);
                } catch (Exception ex) {
                    continue;
                }
                if (dtObj.isBefore(start) || dtObj.isAfter(end)) {
                    continue;
                }
                Double val = toFloatOrNull(firstPresent(row, "Value", "Close", "LatestValue"));
                if (val == null) {
                    continue;
                }
                if (unit == null) {
                    unit = textOrNull(TradingEconomicsApiSupport.ciGet(row, "Unit"));
                }
                if (frequency == null) {
                    frequency = textOrNull(TradingEconomicsApiSupport.ciGet(row, "Frequency"));
                }
                data.add(Map.of("date", dtObj.toString(), "value", val));
            }
            data.sort(Comparator.comparing(v -> String.valueOf(v.get("date"))));
            if (data.isEmpty()) {
                return asResponse(
                        errPayload("no_data", "No Trading Economics data found for this country and indicator."),
                        HttpStatus.NOT_FOUND);
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("from", start.toString());
            metadata.put("to", end.toString());
            metadata.put("points", data.size());
            metadata.put("provider", "Trading Economics");
            return asResponse(
                    okPayload(Map.of(
                            "country", countryName,
                            "indicator", indicatorName,
                            "unit", unit,
                            "frequency", frequency,
                            "data", data,
                            "metadata", metadata)),
                    HttpStatus.OK);
        } catch (TradingEconomicsHttpException ex) {
            var handled = handleTeError(ex);
            return asResponse(handled.payload(), HttpStatus.valueOf(handled.status()));
        }
    }

    private Object cacheGet(String key, long ttlSec) {
        CacheEntry entry = cache.get(key);
        if (entry == null || System.nanoTime() > entry.expiresAtNs) {
            return null;
        }
        return entry.value;
    }

    private void cachePut(String key, Object value) {
        cache.put(key, new CacheEntry(System.nanoTime() + TTL_DAY * 1_000_000_000L, value));
    }

    private Object countryCacheGet(String key) {
        CacheEntry entry = countryCache.get(key);
        if (entry == null || System.nanoTime() > entry.expiresAtNs) {
            return null;
        }
        return entry.value;
    }

    private void countryCachePut(String key, Object value) {
        countryCache.put(key, new CacheEntry(System.nanoTime() + TTL_COUNTRY_SNAPSHOT * 1_000_000_000L, value));
        if (countryCache.size() > 120) {
            countryCache.entrySet().stream()
                    .sorted(Comparator.comparingLong(e -> e.getValue().expiresAtNs))
                    .limit(20)
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(countryCache::remove);
        }
    }

    private static Map<String, Object> okPayload(Map<String, Object> payload) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", SOURCE);
        out.put("status", "ok");
        out.putAll(payload);
        return out;
    }

    private static Map<String, Object> errPayload(String status, String message) {
        return Map.of("source", SOURCE, "status", status, "message", message);
    }

    private static Map<String, Object> missingKeyPayload() {
        return errPayload("missing_api_key", "Trading Economics API key is not configured.");
    }

    private static HandledError handleTeError(TradingEconomicsHttpException ex) {
        int status = ex.statusCode();
        if (status == 401 || status == 403) {
            return new HandledError(401, errPayload("auth_error", "Trading Economics API key was rejected or does not have access."));
        }
        if (status == 429) {
            return new HandledError(429, errPayload("rate_limited", "Trading Economics rate limit exceeded."));
        }
        int code = Math.max(400, Math.min(status > 0 ? status : 502, 599));
        return new HandledError(code, errPayload("upstream_error", "Trading Economics upstream returned HTTP " + (status > 0 ? status : 502) + "."));
    }

    private static ResponseEntity<Map<String, Object>> asResponse(Map<String, Object> payload, HttpStatus status) {
        return ResponseEntity.status(status).body(payload);
    }

    private static String normalizeCountryName(String raw) {
        return String.join(" ", String.valueOf(raw != null ? raw : "").trim().split("\\s+"));
    }

    private static String textOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String safeIsoDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.length() >= 10 ? raw.substring(0, 10) : raw;
    }

    private static Double toFloatOrNull(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Object firstPresent(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                    Object val = entry.getValue();
                    if (val != null && !String.valueOf(val).isBlank()) {
                        return val;
                    }
                }
            }
        }
        return null;
    }

    private record CacheEntry(long expiresAtNs, Object value) {}

    private record HandledError(int status, Map<String, Object> payload) {}
}
