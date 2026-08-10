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

/** TE indicator browser ({@code /api/te/indikator/*}). Port {@code te_indicator_router.py}. */
@RestController
@RequestMapping("/api/te/indikator")
@RequiredArgsConstructor
public class TeIndicatorController {

    private static final Map<String, String> OBLIBENE_INDIKATORY = buildFavorites();
    private static final long CACHE_TTL_SEC = 3600;

    private final TradingEconomicsApiSupport teApi;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @GetMapping("/seznam")
    public Map<String, Object> listIndicators() {
        return Map.of(
                "celkem", OBLIBENE_INDIKATORY.size(),
                "poznamka",
                        "Nazev ukazatele v URL: anglicky, malymi pismeny, mezery = pomlcky. Napr. 'inflation-rate'",
                "ukazatele", OBLIBENE_INDIKATORY);
    }

    @GetMapping("/{indikator}")
    public Map<String, Object> getIndicatorAllCountries(
            @PathVariable("indikator") String indikator,
            @RequestParam(value = "region", required = false) String region) {
        String path = "/country/all/" + indikator.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> items = getCached(path, Map.of("f", "json"));
        List<Map<String, Object>> countries = formatAllCountries(items);
        String ceskyNazev = OBLIBENE_INDIKATORY.getOrDefault(indikator.toLowerCase(Locale.ROOT), indikator);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("indikator", indikator);
        out.put("nazev", ceskyNazev);
        out.put("pocet_zemi", countries.size());
        out.put("jednotka", countries.isEmpty() ? null : countries.get(0).get("jednotka"));
        out.put("frekvence", countries.isEmpty() ? null : countries.get(0).get("frekvence"));
        out.put("zeme", countries);
        out.put("region", region);
        return out;
    }

    @GetMapping("/{indikator}/historie")
    public Map<String, Object> getIndicatorHistorical(
            @PathVariable("indikator") String indikator,
            @RequestParam("zeme") String zeme,
            @RequestParam(value = "od", required = false) String od,
            @RequestParam(value = "do", required = false) String doParam) {
        String zemeList = String.join(",", java.util.Arrays.stream(zeme.split(","))
                .map(String::trim)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .toList());
        String path = buildHistoricalPath(zemeList, indikator.toLowerCase(Locale.ROOT), od, doParam);
        List<Map<String, Object>> items = getCached(path, Map.of());
        Map<String, List<Map<String, Object>>> byCountry = formatHistorical(items);
        if (byCountry.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Zadna historicka data pro ukazatel '" + indikator + "' a zeme '" + zeme + "'");
        }
        String ceskyNazev = OBLIBENE_INDIKATORY.getOrDefault(indikator.toLowerCase(Locale.ROOT), indikator);
        return Map.of(
                "indikator", indikator,
                "nazev", ceskyNazev,
                "zeme", List.copyOf(byCountry.keySet()),
                "od", od != null ? od : "",
                "do", doParam != null ? doParam : "",
                "data", byCountry);
    }

    @GetMapping("/{indikator}/top")
    public Map<String, Object> getIndicatorRanking(
            @PathVariable("indikator") String indikator,
            @RequestParam(value = "razeni", defaultValue = "desc") String razeni,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        String path = "/country/all/" + indikator.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> items = getCached(path, Map.of("f", "json"));
        List<Map<String, Object>> countries = formatAllCountries(items);
        boolean reverse = "desc".equalsIgnoreCase(razeni);
        List<Map<String, Object>> ranked = countries.stream()
                .filter(c -> c.get("hodnota") != null)
                .sorted((a, b) -> {
                    double av = ((Number) a.get("hodnota")).doubleValue();
                    double bv = ((Number) b.get("hodnota")).doubleValue();
                    return reverse ? Double.compare(bv, av) : Double.compare(av, bv);
                })
                .limit(Math.max(1, Math.min(limit, 196)))
                .toList();
        List<Map<String, Object>> withRank = new ArrayList<>();
        int i = 1;
        for (Map<String, Object> item : ranked) {
            Map<String, Object> row = new LinkedHashMap<>(item);
            row.put("poradi", i++);
            withRank.add(row);
        }
        String ceskyNazev = OBLIBENE_INDIKATORY.getOrDefault(indikator.toLowerCase(Locale.ROOT), indikator);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("indikator", indikator);
        out.put("nazev", ceskyNazev);
        out.put("razeni", razeni);
        out.put("pocet_celkem", countries.size());
        out.put("zobrazeno", withRank.size());
        out.put("jednotka", withRank.isEmpty() ? null : withRank.get(0).get("jednotka"));
        out.put("zebricek", withRank);
        return out;
    }

    @GetMapping("/{indikator}/ticker/{ticker}/historie")
    public Map<String, Object> getByTickerHistorical(
            @PathVariable("indikator") String indikator,
            @PathVariable("ticker") String ticker,
            @RequestParam(value = "od", required = false) String od) {
        String path = od != null && !od.isBlank()
                ? "/historical/ticker/" + ticker.toUpperCase(Locale.ROOT) + "/" + od
                : "/historical/ticker/" + ticker.toUpperCase(Locale.ROOT);
        List<Map<String, Object>> items = getCached(path, Map.of());
        List<Map<String, Object>> series = items.stream()
                .filter(item -> item.get("Value") != null)
                .map(item -> {
                    String dt = TradingEconomicsApiSupport.ciGet(item, "DateTime");
                    return Map.<String, Object>of(
                            "date", dt.length() >= 10 ? dt.substring(0, 10) : dt,
                            "value", item.get("Value"));
                })
                .sorted(Comparator.comparing(v -> String.valueOf(v.get("date"))))
                .toList();
        if (series.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Zadna data pro ticker '" + ticker + "'");
        }
        return Map.of(
                "ticker", ticker.toUpperCase(Locale.ROOT),
                "indikator", indikator,
                "od", od != null ? od : "",
                "pocet_zaznamu", series.size(),
                "data", series);
    }

    private List<Map<String, Object>> getCached(String path, Map<String, String> params) {
        String key = path + "?" + params;
        CacheEntry entry = cache.get(key);
        long now = System.nanoTime();
        if (entry != null && now < entry.expiresAtNs) {
            return entry.payload();
        }
        List<Map<String, Object>> data = fetch(path, params);
        cache.put(key, new CacheEntry(now + CACHE_TTL_SEC * 1_000_000_000L, data));
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
        String base = "/historical/country/" + zeme + "/indicator/" + indikator;
        if (od != null && !od.isBlank() && doParam != null && !doParam.isBlank()) {
            return base + "/" + od + "/" + doParam;
        }
        if (od != null && !od.isBlank()) {
            return base + "/" + od;
        }
        return base;
    }

    private static List<Map<String, Object>> formatAllCountries(List<Map<String, Object>> items) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> item : items) {
            if (item.get("LatestValue") == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("zeme", TradingEconomicsApiSupport.ciGet(item, "Country"));
            row.put("hodnota", item.get("LatestValue"));
            row.put("datum", TradingEconomicsApiSupport.ciGet(item, "LatestValueDate"));
            row.put("predchozi", item.get("PreviousValue"));
            row.put("predchozi_datum", TradingEconomicsApiSupport.ciGet(item, "PreviousValueDate"));
            row.put("jednotka", TradingEconomicsApiSupport.ciGet(item, "Unit"));
            row.put("frekvence", TradingEconomicsApiSupport.ciGet(item, "Frequency"));
            row.put("zdroj", TradingEconomicsApiSupport.ciGet(item, "Source"));
            row.put("ticker", TradingEconomicsApiSupport.ciGet(item, "HistoricalDataSymbol"));
            out.add(row);
        }
        out.sort(Comparator.comparing(v -> String.valueOf(v.get("zeme"))));
        return out;
    }

    private static Map<String, List<Map<String, Object>>> formatHistorical(List<Map<String, Object>> items) {
        Map<String, List<Map<String, Object>>> byCountry = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            if (item.get("Value") == null) {
                continue;
            }
            String country = TradingEconomicsApiSupport.ciGet(item, "Country");
            if (country.isBlank()) {
                country = "Unknown";
            }
            String dt = TradingEconomicsApiSupport.ciGet(item, "DateTime");
            byCountry.computeIfAbsent(country, ignored -> new ArrayList<>())
                    .add(Map.of("date", dt.length() >= 10 ? dt.substring(0, 10) : dt, "value", item.get("Value")));
        }
        for (List<Map<String, Object>> series : byCountry.values()) {
            series.sort(Comparator.comparing(v -> String.valueOf(v.get("date"))));
        }
        return byCountry;
    }

    private static Map<String, String> buildFavorites() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("gdp", "HDP (mld. USD)");
        out.put("gdp-growth-rate", "Rust HDP (ctvrtletni %)");
        out.put("gdp-annual-growth-rate", "Rust HDP (mezirocni %)");
        out.put("gdp-per-capita", "HDP na obyvatele (USD)");
        out.put("gdp-per-capita-ppp", "HDP na obyvatele - PPP (USD)");
        out.put("inflation-rate", "Inflace (mezirocni %)");
        out.put("inflation-rate-mom", "Inflace (mesicni %)");
        out.put("core-inflation-rate", "Jadrova inflace (%)");
        out.put("producer-prices-change", "Ceny vyrobcu - zmena (%)");
        out.put("unemployment-rate", "Mira nezamestnanosti (%)");
        out.put("employment-rate", "Mira zamestnanosti (%)");
        out.put("wages", "Prumerna mzda");
        out.put("wage-growth", "Rust mezd (%)");
        out.put("minimum-wages", "Minimalni mzda");
        out.put("youth-unemployment-rate", "Nezamestnanost mladych (%)");
        out.put("government-debt-to-gdp", "Statni dluh (% HDP)");
        out.put("government-budget", "Saldo statniho rozpoctu (% HDP)");
        out.put("government-revenues", "Vladni prijmy (% HDP)");
        out.put("government-spending", "Vladni vydaje (% HDP)");
        out.put("government-debt", "Statni dluh (mld. domaci meny)");
        out.put("balance-of-trade", "Obchodni bilance");
        out.put("current-account", "Bezny ucet platebni bilance");
        out.put("current-account-to-gdp", "Bezny ucet (% HDP)");
        out.put("exports", "Vyvoz");
        out.put("imports", "Dovoz");
        out.put("foreign-direct-investment", "Prime zahranicni investice");
        out.put("external-debt", "Zahranicni dluh");
        out.put("interest-rate", "Zakladni urokova sazba centralni banky (%)");
        out.put("lending-rate", "Uverova sazba bank (%)");
        out.put("deposit-interest-rate", "Sazba vkladu (%)");
        out.put("money-supply-m1", "Penezni zasoba M1");
        out.put("money-supply-m2", "Penezni zasoba M2");
        out.put("money-supply-m3", "Penezni zasoba M3");
        out.put("credit-to-private-sector", "Uvery soukromemu sektoru");
        out.put("bank-lending-rate", "Urokova sazba bank (%)");
        out.put("housing-index", "Index cen bydleni");
        out.put("house-price-index", "Index cen domu");
        out.put("residential-property-prices", "Ceny rezidencnich nemovitosti");
        out.put("mortgage-rate", "Hypotecni sazba (%)");
        out.put("industrial-production", "Prumyslova vyroba (%)");
        out.put("industrial-production-mom", "Prumyslova vyroba (mesicni %)");
        out.put("manufacturing-pmi", "PMI prumyslu");
        out.put("services-pmi", "PMI sluzeb");
        out.put("composite-pmi", "Kompozitni PMI");
        out.put("business-confidence", "Podnikatelska duvera");
        out.put("retail-sales", "Maloobchodni trzby (%)");
        out.put("retail-sales-mom", "Maloobchodni trzby (mesicni %)");
        out.put("car-registrations", "Registrace aut");
        out.put("consumer-confidence", "Spotrebitelska duvera");
        out.put("consumer-spending", "Spotreba domacnosti");
        out.put("consumer-credit", "Spotrebitelske uvery");
        out.put("stock-market", "Akciovy index");
        out.put("10-year-bond-yield", "Vynos statniho dluhopisu 10 let (%)");
        out.put("2-year-note-yield", "Vynos statniho dluhopisu 2 roky (%)");
        out.put("government-bond-yield", "Vynos statniho dluhopisu (%)");
        out.put("currency", "Devizovy kurz");
        out.put("population", "Pocet obyvatel (mil.)");
        out.put("gdp-growth", "Rocni rust HDP (%)");
        return Map.copyOf(out);
    }

    private record CacheEntry(long expiresAtNs, List<Map<String, Object>> payload) {}
}
