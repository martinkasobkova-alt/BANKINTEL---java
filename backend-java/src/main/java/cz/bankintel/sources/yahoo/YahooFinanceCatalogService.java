package cz.bankintel.sources.yahoo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.domain.dto.SourceDtos.SourceCreateRequest;
import cz.bankintel.domain.entity.SourceEntity;
import cz.bankintel.repository.SourceRepository;
import cz.bankintel.service.sources.SourceService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class YahooFinanceCatalogService {

    private static final Logger log = LoggerFactory.getLogger(YahooFinanceCatalogService.class);
    private static final String CATALOG_PATH = "Yahoo Finance";
    private static final String DEFAULT_PREVIEW_START = "1990-01-01";

    private static final Map<String, Map<String, String>> TICKER_CATALOG = Map.ofEntries(
            Map.entry("^NDX", Map.of("name", "Nasdaq-100", "category", "Index")),
            Map.entry("^GSPC", Map.of("name", "S&P 500", "category", "Index")),
            Map.entry("QQQ", Map.of("name", "Invesco QQQ Trust", "category", "ETF")),
            Map.entry("TQQQ", Map.of("name", "ProShares UltraPro QQQ", "category", "ETF")),
            Map.entry("SPY", Map.of("name", "SPDR S&P 500 ETF", "category", "ETF")),
            Map.entry("UPRO", Map.of("name", "ProShares UltraPro S&P500", "category", "ETF")),
            Map.entry("SSO", Map.of("name", "ProShares Ultra S&P500", "category", "ETF")),
            Map.entry("^VIX", Map.of("name", "CBOE Volatility Index", "category", "Index")),
            Map.entry("GLD", Map.of("name", "SPDR Gold Shares", "category", "ETF")),
            Map.entry("GC=F", Map.of("name", "Gold Futures", "category", "Commodity")));

    private final SourceRepository sourceRepository;
    private final SourceService sourceService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    @Transactional(readOnly = true)
    public Map<String, Object> getCatalog() {
        List<Map<String, Object>> sets = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> entry : TICKER_CATALOG.entrySet()) {
            sets.add(catalogRow(entry.getKey(), entry.getValue().get("name"), entry.getValue().get("category"), false));
        }
        for (SourceEntity source : sourceRepository.findAllByOrderByCreatedAtDesc()) {
            if (!"yahoo_finance".equalsIgnoreCase(source.getSourceType())) {
                continue;
            }
            String ticker = readTicker(source);
            sets.add(catalogRow(source.getId(), source.getName(), "Saved", true, ticker));
        }
        Map<String, Object> category = new LinkedHashMap<>();
        category.put("path", CATALOG_PATH);
        category.put("name", CATALOG_PATH);
        category.put("children", List.of());
        category.put("sets", sets);
        return Map.of("categories", List.of(category), "total_sets", sets.size());
    }

    public Map<String, Object> preview(String setId, String startDate, String endDate, String interval) {
        String ticker = resolveTicker(setId);
        if (ticker.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chybí ticker (set_id).");
        }
        String start = startDate != null && !startDate.isBlank() ? startDate : DEFAULT_PREVIEW_START;
        String freq = interval != null && !interval.isBlank() ? interval : "1d";
        List<Map<String, Object>> records = fetchRecords(ticker, start, endDate, freq);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("source_type", "yahoo_finance");
        out.put("set_id", ticker);
        out.put("name", displayName(ticker));
        out.put("chart_frequency", "D");
        out.put("chart_frequency_label", "Denní");
        out.put("records", records);
        out.put("record_count", records.size());
        return out;
    }

    @Transactional
    public Map<String, Object> addSource(Map<String, Object> payload) {
        String ticker = resolveTicker(String.valueOf(payload.getOrDefault("set_id", payload.get("ticker"))));
        if (ticker.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chybí ticker (set_id).");
        }
        String interval = String.valueOf(payload.getOrDefault("interval", "1d")).trim();
        if (interval.isBlank()) {
            interval = "1d";
        }
        String displayName = String.valueOf(payload.getOrDefault("name", "")).trim();
        if (displayName.isBlank()) {
            displayName = displayName(ticker);
        }
        if (sourceRepository.existsByName(displayName)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Zdroj '" + displayName + "' už existuje.");
        }
        SourceCreateRequest request = new SourceCreateRequest(
                displayName,
                "yahoo_finance",
                "https://query1.finance.yahoo.com",
                "/v8/finance/chart/" + ticker,
                "GET",
                "none",
                null,
                Map.of("Accept", "application/json", "User-Agent", "bankintel/1.0"),
                Map.of("ticker", ticker, "interval", interval, "start_date", DEFAULT_PREVIEW_START),
                1440,
                true,
                displayName,
                null);
        Map<String, Object> created = sourceService.createSource(request);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", created.get("id"));
        out.put("name", displayName);
        out.put("set_id", ticker);
        out.put("full_path", CATALOG_PATH + " > " + displayName);
        return out;
    }

    private List<Map<String, Object>> fetchRecords(String ticker, String startDate, String endDate, String interval) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            long period1 = LocalDate.parse(startDate).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
            long period2 = endDate != null && !endDate.isBlank()
                    ? LocalDate.parse(endDate).atTime(23, 59, 59).toEpochSecond(ZoneOffset.UTC)
                    : java.time.Instant.now().getEpochSecond();
            // Drive pouzivalo hrubsi interval (tydenni/mesicni) pro dlouhe rozsahy, protoze vykresleni
            // nekolika tisic denich bodu zamrzalo "Moznosti grafu" panel - skutecna pricina (O(n^2)
            // vypocet z-score/anomalii v chartSeriesStatistics.js) uz je opravena, takze denni
            // granularita je ted bezpecna i pro cele obdobi a muze zustat skutecne denni.
            URI uri = URI.create("https://query1.finance.yahoo.com/v8/finance/chart/"
                    + ticker
                    + "?period1="
                    + period1
                    + "&period2="
                    + period2
                    + "&interval="
                    + interval);
            HttpRequest request = HttpRequest.newBuilder().uri(uri).timeout(Duration.ofSeconds(20)).GET().header("User-Agent", "bankintel/1.0").build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                return out;
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode result = root.path("chart").path("result");
            if (!result.isArray() || result.isEmpty()) {
                return out;
            }
            JsonNode first = result.get(0);
            JsonNode timestamps = first.path("timestamp");
            JsonNode quotes = first.path("indicators").path("quote");
            if (!quotes.isArray() || quotes.isEmpty()) {
                return out;
            }
            JsonNode quote = quotes.get(0);
            JsonNode closes = quote.path("close");
            JsonNode opens = quote.path("open");
            JsonNode highs = quote.path("high");
            JsonNode lows = quote.path("low");
            JsonNode volumes = quote.path("volume");
            // Rozsah dat uz je omezeny period1/period2 z pozadovaneho startDate/endDate (pro "MAX"
            // od 1990) - drivejsi oriznuti na poslednich 1000 baru tise zahazovalo vse starsi nez
            // ~4 roky bez ohledu na skutecne pozadovany rozsah, takze "MAX"/"cele obdobi" u akcii
            // nikdy nezobrazilo vic nez ~4 roky.
            for (int i = 0; i < timestamps.size(); i++) {
                if (closes.get(i).isNull()) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("date", java.time.Instant.ofEpochSecond(timestamps.get(i).asLong()).toString().substring(0, 10));
                row.put("value", closes.get(i).asDouble());
                row.put("open", opens.get(i).isNull() ? null : opens.get(i).asDouble());
                row.put("high", highs.get(i).isNull() ? null : highs.get(i).asDouble());
                row.put("low", lows.get(i).isNull() ? null : lows.get(i).asDouble());
                row.put("close", closes.get(i).asDouble());
                row.put("volume", volumes.get(i).isNull() ? null : volumes.get(i).asLong());
                row.put("ticker", ticker);
                out.add(row);
            }
        } catch (Exception ex) {
            log.warn("Yahoo preview failed for {}: {}", ticker, ex.getMessage());
        }
        return out;
    }

    private static Map<String, Object> catalogRow(String setId, String name, String category, boolean saved) {
        return catalogRow(setId, name, category, saved, setId);
    }

    private static Map<String, Object> catalogRow(String setId, String name, String category, boolean saved, String ticker) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("set_id", setId);
        row.put("name", name);
        row.put("kind", "selection");
        row.put("ticker", ticker);
        row.put("category", category);
        if (saved) {
            row.put("saved_source", true);
        }
        return row;
    }

    private static String resolveTicker(String raw) {
        String text = raw != null ? raw.trim() : "";
        if (text.startsWith("^")) {
            return text.toUpperCase(Locale.ROOT);
        }
        return text.toUpperCase(Locale.ROOT);
    }

    private static String displayName(String ticker) {
        Map<String, String> meta = TICKER_CATALOG.get(ticker);
        return meta != null ? meta.get("name") : ticker;
    }

    @SuppressWarnings("unchecked")
    private static String readTicker(SourceEntity source) {
        Object config = source.getConnectorConfig();
        if (config instanceof Map<?, ?> map) {
            Object queryParams = map.get("query_params");
            if (queryParams instanceof Map<?, ?> qp) {
                Object ticker = qp.get("ticker");
                if (ticker != null) {
                    return String.valueOf(ticker);
                }
            }
        }
        return source.getName();
    }
}
