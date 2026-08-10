package cz.bankintel.controller.sources;

import cz.bankintel.sources.tradingeconomics.TradingEconomicsApiSupport;
import cz.bankintel.sources.tradingeconomics.TradingEconomicsApiSupport.TradingEconomicsHttpException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** TE country browser ({@code /api/te/country/*}). Port {@code te_country_router.py}. */
@RestController
@RequestMapping("/api/te/country")
@RequiredArgsConstructor
public class TeCountryController {

    private static final List<String> TE_GROUPS = List.of(
            "gdp", "prices", "labour", "trade", "government", "money", "housing", "health", "business", "consumer",
            "markets", "taxes", "climate", "overview");
    private static final long CACHE_TTL_SEC = 3600;

    private final TradingEconomicsApiSupport teApi;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @GetMapping("/skupiny")
    public Map<String, Object> listGroups() {
        return Map.of(
                "skupiny", TE_GROUPS,
                "pouziti", "Pridej ?skupina=gdp k endpointu /api/te/country/{zeme}");
    }

    @GetMapping("/{zeme}")
    public Map<String, Object> getCountryAll(
            @PathVariable("zeme") String zeme,
            @RequestParam(value = "skupina", required = false) String skupina) {
        String path = "/country/" + zeme.toLowerCase(Locale.ROOT);
        Map<String, String> params = new LinkedHashMap<>();
        if (skupina != null && !skupina.isBlank()) {
            if (!TE_GROUPS.contains(skupina.toLowerCase(Locale.ROOT))) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Neznama skupina '" + skupina + "'. Dostupne: " + String.join(", ", TE_GROUPS));
            }
            params.put("group", skupina.toLowerCase(Locale.ROOT));
        }
        List<Map<String, Object>> items = getCached(path, params);
        Map<String, List<Map<String, Object>>> grouped = formatSnapshot(items);
        return Map.of(
                "zeme", zeme,
                "skupina", skupina == null || skupina.isBlank() ? "vse" : skupina,
                "celkem_ukazatelu", items.size(),
                "pocet_skupin", grouped.size(),
                "data", grouped);
    }

    @GetMapping("/{zeme}/historie/{indikator}")
    public Map<String, Object> getCountryHistorical(
            @PathVariable("zeme") String zeme,
            @PathVariable("indikator") String indikator,
            @RequestParam(value = "od", required = false) String od,
            @RequestParam(value = "do", required = false) String doParam) {
        String path = buildHistoricalPath(zeme, indikator, od, doParam);
        List<Map<String, Object>> items = getCached(path, Map.of());
        List<Map<String, Object>> series = formatHistorical(items);
        if (series.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Zadna historicka data pro '" + indikator + "' v zemi '" + zeme + "'");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("zeme", zeme);
        out.put("indikator", indikator);
        out.put("od", od);
        out.put("do", doParam);
        out.put("pocet_zaznamu", series.size());
        out.put("data", series);
        return out;
    }

    @GetMapping("/{zeme}/srovnani/{indikator}")
    public Map<String, Object> getMultiCountryHistorical(
            @PathVariable("zeme") String zeme,
            @PathVariable("indikator") String indikator,
            @RequestParam(value = "od", required = false) String od,
            @RequestParam(value = "do", required = false) String doParam) {
        String zemeList = String.join(",", java.util.Arrays.stream(zeme.split(","))
                .map(String::trim)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .toList());
        String path = buildHistoricalPath(zemeList, indikator.toLowerCase(Locale.ROOT), od, doParam);
        List<Map<String, Object>> items = getCached(path, Map.of());
        List<Map<String, Object>> series = formatHistorical(items);

        Map<String, List<Map<String, Object>>> byCountry = new LinkedHashMap<>();
        for (Map<String, Object> point : series) {
            String c = String.valueOf(point.get("zeme"));
            byCountry.computeIfAbsent(c, ignored -> new ArrayList<>())
                    .add(Map.of("date", point.get("date"), "value", point.get("value")));
        }

        return Map.of(
                "zeme", List.of(zeme.split(",")),
                "indikator", indikator,
                "od", od != null ? od : "",
                "do", doParam != null ? doParam : "",
                "data", byCountry);
    }

    private List<Map<String, Object>> getCached(String path, Map<String, String> params) {
        String key = path + "?" + params;
        CacheEntry entry = cache.get(key);
        long now = System.nanoTime();
        if (entry != null && now < entry.expiresAtNs && entry.payload instanceof List<?> list) {
            return copyList(list);
        }
        List<Map<String, Object>> data = fetch(path, params);
        cache.put(key, new CacheEntry(now + CACHE_TTL_SEC * 1_000_000_000L, copyList(data)));
        return data;
    }

    private List<Map<String, Object>> fetch(String path, Map<String, String> params) {
        if (teApi.apiKey().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "TRADING_ECONOMICS_API_KEY neni nastaveny v environment variables");
        }
        try {
            return teApi.getJsonList(path, params);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        } catch (TradingEconomicsHttpException ex) {
            int st = ex.statusCode() > 0 ? ex.statusCode() : 502;
            if (st == 401 || st == 403) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Neplatny API klic nebo nedostatecna opravneni");
            }
            if (st == 404) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Data nenalezena: " + path);
            }
            String msg = ex.getMessage() != null ? ex.getMessage().substring(0, Math.min(ex.getMessage().length(), 200)) : "";
            throw new ResponseStatusException(HttpStatus.valueOf(Math.max(400, Math.min(st, 599))), "Trading Economics API chyba: " + msg);
        }
    }

    private static String buildHistoricalPath(String zeme, String indikator, String od, String doParam) {
        String base = "/historical/country/" + zeme.toLowerCase(Locale.ROOT) + "/indicator/" + indikator.toLowerCase(Locale.ROOT);
        if (od != null && !od.isBlank() && doParam != null && !doParam.isBlank()) {
            return base + "/" + od + "/" + doParam;
        }
        if (od != null && !od.isBlank()) {
            return base + "/" + od;
        }
        return base;
    }

    private static Map<String, List<Map<String, Object>>> formatSnapshot(List<Map<String, Object>> items) {
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            String group = TradingEconomicsApiSupport.ciGet(item, "CategoryGroup");
            if (group.isBlank()) {
                group = "Other";
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("nazev", TradingEconomicsApiSupport.ciGet(item, "Category"));
            row.put("hodnota", item.get("LatestValue"));
            row.put("datum", TradingEconomicsApiSupport.ciGet(item, "LatestValueDate"));
            row.put("predchozi", item.get("PreviousValue"));
            row.put("predchozi_datum", TradingEconomicsApiSupport.ciGet(item, "PreviousValueDate"));
            row.put("jednotka", TradingEconomicsApiSupport.ciGet(item, "Unit"));
            row.put("frekvence", TradingEconomicsApiSupport.ciGet(item, "Frequency"));
            row.put("zdroj", TradingEconomicsApiSupport.ciGet(item, "Source"));
            row.put("ticker", TradingEconomicsApiSupport.ciGet(item, "HistoricalDataSymbol"));
            row.put("url", TradingEconomicsApiSupport.ciGet(item, "URL"));
            grouped.computeIfAbsent(group, ignored -> new ArrayList<>()).add(row);
        }
        for (List<Map<String, Object>> group : grouped.values()) {
            group.sort(Comparator.comparing(v -> String.valueOf(v.get("nazev"))));
        }
        return grouped;
    }

    private static List<Map<String, Object>> formatHistorical(List<Map<String, Object>> items) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> item : items) {
            if (item.get("Value") == null) {
                continue;
            }
            String dt = TradingEconomicsApiSupport.ciGet(item, "DateTime");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", dt.length() >= 10 ? dt.substring(0, 10) : dt);
            row.put("value", item.get("Value"));
            row.put("zeme", TradingEconomicsApiSupport.ciGet(item, "Country"));
            row.put("kategorie", TradingEconomicsApiSupport.ciGet(item, "Category"));
            out.add(row);
        }
        out.sort(Comparator
                .comparing((Map<String, Object> x) -> String.valueOf(x.get("zeme")))
                .thenComparing(x -> String.valueOf(x.get("date"))));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> copyList(List<?> source) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : source) {
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

    private record CacheEntry(long expiresAtNs, List<Map<String, Object>> payload) {}
}
