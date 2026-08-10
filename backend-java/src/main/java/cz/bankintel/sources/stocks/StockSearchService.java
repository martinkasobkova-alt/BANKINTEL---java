package cz.bankintel.sources.stocks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Stock search via Yahoo Finance symbol search — port {@code stock_search_service.py}. */
@Service
public class StockSearchService {

    private static final Logger log = LoggerFactory.getLogger(StockSearchService.class);
    private static final Set<String> SEARCH_QUOTE_TYPES = Set.of("EQUITY", "ETF", "INDEX", "MUTUALFUND", "FUTURE", "CURRENCY");
    // Vysledky /stocks/search nesou tenhle start_date primo v query_params kazdeho radku - frontend
    // (catalogPreviewBody.js) ho pak jen respektuje, takze "2020" tise omezovalo VSECHNY nasledne
    // nahledy na ~posledních 5-6 let bez ohledu na to, ze uzivatel chce cele obdobi (MAX). Sjednoceno
    // s YahooFinanceCatalogService/CatalogPreviewOrchestrator, ktere uz "1990-01-01" pouzivaji.
    private static final String DEFAULT_PREVIEW_START = "1990-01-01";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public StockSearchService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public Map<String, Object> search(String query, boolean allowLlm, int limit) {
        String q = query != null ? query.trim() : "";
        if (q.length() < 2) {
            return Map.of(
                    "ok", false,
                    "source_bucket", "stocks",
                    "query", q,
                    "error", "Zadejte alespoň 2 znaky.",
                    "resolver", null,
                    "results", List.of());
        }
        int cap = Math.max(1, Math.min(limit, 12));
        String resolverQuery = stockResolverQuery(q);
        List<Map<String, Object>> yahooCandidates = yahooSymbolSearchMatches(resolverQuery.isBlank() ? q : resolverQuery, cap);
        List<Map<String, Object>> results = fetchMarketSnapshots(yahooCandidates, cap);
        Map<String, Object> resolver = new LinkedHashMap<>();
        resolver.put("preferred_source", "yahoo_finance");
        resolver.put("resolver_query", resolverQuery.isBlank() ? q : resolverQuery);
        resolver.put("ticker_candidates", yahooCandidates);
        resolver.put("yahoo_symbol_search_count", yahooCandidates.size());
        resolver.put("search_strategy", "yahoo_symbol_search");
        resolver.put("allow_llm", allowLlm);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("source_bucket", "stocks");
        out.put("query", q);
        out.put("resolver", resolver);
        out.put("results", results);
        out.put("best_match", results.isEmpty() ? null : results.get(0));
        out.put("stock_search_path", "/search/stocks");
        return out;
    }

    private static String stockResolverQuery(String query) {
        Set<String> marketWords = Set.of(
                "akcie", "akcii", "akciova", "akciovy", "stock", "stocks", "share", "shares", "equity", "ticker",
                "cena", "price", "kurz", "burza", "trh");
        return java.util.Arrays.stream((query == null ? "" : query).trim().split("\\s+"))
                .map(token -> token.replaceAll("^[^\\p{L}\\p{N}^=.]+|[^\\p{L}\\p{N}^=.]+$", ""))
                .filter(token -> !token.isBlank())
                .filter(token -> !marketWords.contains(token.toLowerCase(Locale.ROOT)))
                .collect(Collectors.joining(" "))
                .trim();
    }

    private List<Map<String, Object>> yahooSymbolSearchMatches(String query, int maxMatches) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new java.util.LinkedHashSet<>();
        String url = "https://query2.finance.yahoo.com/v1/finance/search?q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&quotesCount="
                + maxMatches
                + "&newsCount=0";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "bankintel-bi/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body() == null) {
                return out;
            }
            Map<String, Object> body = objectMapper.readValue(response.body(), new TypeReference<>() {});
            Object quotesObj = body.get("quotes");
            if (!(quotesObj instanceof List<?> quotes)) {
                return out;
            }
            for (Object itemObj : quotes) {
                if (!(itemObj instanceof Map<?, ?> item)) {
                    continue;
                }
                String sym = stringOrBlank(item.get("symbol"));
                if (sym.isBlank() || seen.contains(sym)) {
                    continue;
                }
                String quoteType = stringOrBlank(item.get("quoteType")).toUpperCase(Locale.ROOT);
                if (!quoteType.isBlank() && !SEARCH_QUOTE_TYPES.contains(quoteType)) {
                    continue;
                }
                seen.add(sym);
                String name = stringOrBlank(item.get("shortname"));
                if (name.isBlank()) {
                    name = stringOrBlank(item.get("longname"));
                }
                if (name.isBlank()) {
                    name = sym;
                }
                out.add(Map.of(
                        "ticker", sym,
                        "exchange", stringOrBlank(item.get("exchDisp")).isBlank()
                                ? stringOrBlank(item.get("exchange"))
                                : stringOrBlank(item.get("exchDisp")),
                        "confidence", 0.88,
                        "reason", name,
                        "category", stringOrBlank(item.get("typeDisp")).isBlank() ? quoteType : stringOrBlank(item.get("typeDisp")),
                        "source", "yahoo_symbol_search"));
                if (out.size() >= maxMatches) {
                    break;
                }
            }
        } catch (Exception ex) {
            log.warn("Yahoo symbol search failed: {}", ex.getMessage());
        }
        return out;
    }

    private List<Map<String, Object>> fetchMarketSnapshots(List<Map<String, Object>> candidates, int cap) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> cand : candidates.subList(0, Math.min(candidates.size(), cap))) {
            String ticker = stringOrBlank(cand.get("ticker"));
            if (ticker.isBlank()) {
                continue;
            }
            String name = stringOrBlank(cand.get("reason"));
            String category = stringOrBlank(cand.get("category"));
            Map<String, Object> row = yahooRowForTicker(ticker, name, category);
            row.put("exchange", cand.get("exchange"));
            row.put("confidence", cand.get("confidence"));
            row.put("resolver_reason", cand.get("reason"));
            row.put("match_source", cand.get("source"));
            out.add(row);
        }
        return out;
    }

    private static Map<String, Object> yahooRowForTicker(String ticker, String name, String category) {
        String sym = ticker.trim();
        String label = name != null && !name.isBlank() ? name.trim() : sym;
        String cat = category != null && !category.isBlank() ? category.trim() : "Instrument";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", "yahoo_finance");
        out.put("catalog_id", "yahoo_finance");
        out.put("source_type", "yahoo_finance");
        out.put("set_id", sym);
        out.put("name", label);
        out.put("ticker", sym);
        out.put("full_path", "Yahoo Finance > " + cat + " > " + label);
        out.put("category", cat);
        out.put("query_params", Map.of("ticker", sym, "interval", "1d", "start_date", DEFAULT_PREVIEW_START));
        out.put("kind", "set");
        out.put("item_kind", "stock_search");
        return out;
    }

    private static String stringOrBlank(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }
}
