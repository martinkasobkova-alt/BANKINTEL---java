package cz.bankintel.controller.catalog;

import cz.bankintel.sources.stocks.StockSearchService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** POST {@code /api/stocks/search} — port {@code stock_search_routes.py}. */
@RestController
@RequestMapping("/api/stocks")
@RequiredArgsConstructor
public class StockSearchController {

    private final StockSearchService stockSearchService;

    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody(required = false) Map<String, Object> payload) {
        Map<String, Object> body = payload != null ? payload : Map.of();
        String q = stringOrBlank(body.get("query"));
        if (q.isBlank()) {
            q = stringOrBlank(body.get("q"));
        }
        boolean allowLlm = !Set.of("0", "false", "no").contains(stringOrBlank(body.get("allow_llm")).toLowerCase(Locale.ROOT));
        int limit = 5;
        try {
            Object rawLimit = body.get("limit");
            if (rawLimit != null) {
                limit = Integer.parseInt(String.valueOf(rawLimit).trim());
            }
        } catch (NumberFormatException ignored) {
            limit = 5;
        }
        try {
            return stockSearchService.search(q, allowLlm, limit);
        } catch (Exception ex) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("source_bucket", "stocks");
            out.put("query", q);
            out.put("error", "Vyhledávání akcií se nepodařilo dokončit.");
            out.put("results", List.of());
            return out;
        }
    }

    private static String stringOrBlank(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }
}
