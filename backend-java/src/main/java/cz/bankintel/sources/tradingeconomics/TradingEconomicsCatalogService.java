package cz.bankintel.sources.tradingeconomics;

import cz.bankintel.domain.dto.SourceDtos.SourceCreateRequest;
import cz.bankintel.service.sources.SourceService;
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
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** TE katalog — port {@code tradingeconomics_catalog_routes.py}. */
@Service
@RequiredArgsConstructor
public class TradingEconomicsCatalogService {

    private static final String CAT_PATH = "TradingEconomics";
    private static final long COUNTRY_CACHE_TTL_SEC = 6 * 60 * 60;
    private static final long COUNTRY_NODE_CACHE_TTL_SEC = 2 * 60 * 60;

    private static final List<Map.Entry<String, String>> COMMON_INDICATORS = List.of(
            Map.entry("inflation-rate", "Inflation Rate"),
            Map.entry("unemployment-rate", "Unemployment Rate"),
            Map.entry("interest-rate", "Interest Rate"),
            Map.entry("gdp-growth-rate", "GDP Growth Rate"),
            Map.entry("gdp", "GDP"),
            Map.entry("balance-of-trade", "Balance of Trade"),
            Map.entry("government-debt-to-gdp", "Government Debt to GDP"));

    private static final List<String> FALLBACK_COUNTRIES = List.of(
            "Czech Republic", "Germany", "France", "Italy", "Spain", "Poland", "United Kingdom", "United States",
            "Canada", "Japan");

    private static final List<String> TE_GROUP_ORDER = List.of(
            "Overview", "Markets", "GDP", "Labour", "Prices", "Money", "Trade", "Government", "Business", "Consumer",
            "Housing", "Taxes", "Energy", "Health", "Climate");

    private final TradingEconomicsApiSupport teApi;
    private final SourceService sourceService;

    private volatile long countryCacheTs;
    private volatile List<String> countryCache = List.of();
    private final Map<String, CountryNodeCacheEntry> countryNodeCache = new ConcurrentHashMap<>();

    public Map<String, Object> getCatalog() {
        List<String> countries = loadTeCountries();
        List<Map<String, Object>> countryChildren = countries.stream().map(this::buildCountryNodeLazy).toList();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("path", CAT_PATH);
        root.put("name", CAT_PATH);
        root.put("children", countryChildren);
        root.put("sets", List.of());
        root.put(
                "browse_notice",
                "Pseudo-strom je sestaven pro rychlý výběr země a hlavních indikátorů. "
                        + "Pro širší pokrytí použijte AI vyhledávání.");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("categories", List.of(root));
        out.put("total_sets", countries.size() * COMMON_INDICATORS.size());
        out.put(
                "browse_notice",
                "TradingEconomics nepoužívá veřejný hierarchický katalog jako Eurostat; "
                        + "zobrazen je praktický strom země → indikátory.");
        return out;
    }

    public Map<String, Object> getCountryCatalog(String countryName) {
        String country = stringOrBlank(countryName);
        if (country.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing country_name.");
        }
        Map<String, Object> node = buildCountryNodeGrouped(country);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("country", country);
        out.put("country_node", node);
        return out;
    }

    public Map<String, Object> addSource(Map<String, Object> payload) {
        if (teApi.apiKey().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Na serveru není TRADING_ECONOMICS_API_KEY — nelze synchronizovat TradingEconomics.");
        }

        Map<String, Object> safe = payload != null ? payload : Map.of();
        String setId = stringOrBlank(safe.get("set_id"));
        ParsedTeSetId parsed = parseTeSetId(setId);
        Map<String, Object> qpIn =
                safe.get("query_params") instanceof Map<?, ?> m ? toStringObjectMap(m) : new LinkedHashMap<>();
        Map<String, Object> qp = new LinkedHashMap<>(qpIn);

        String symbol = firstNonBlank(
                stringOrBlank(qp.get("historical_symbol")), parsed.symbol() != null ? parsed.symbol() : "");
        symbol = symbol.toUpperCase(Locale.ROOT);
        String country = stringOrBlank(qp.get("country"));
        String indicator = firstNonBlank(stringOrBlank(qp.get("indicator")), stringOrBlank(qp.get("category")));

        if (symbol.isBlank() && (country.isBlank() || indicator.isBlank())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "TradingEconomics add-source vyžaduje ticker (TE|SYMBOL) nebo query_params.country + query_params.indicator.");
        }

        if (!symbol.isBlank()) {
            qp.put("historical_symbol", symbol);
        }
        if (!country.isBlank()) {
            qp.put("country", country);
        }
        if (!indicator.isBlank()) {
            qp.put("indicator", indicator);
        }

        String displayName = stringOrBlank(safe.get("name"));
        if (displayName.isBlank()) {
            displayName = symbol.isBlank()
                    ? "TradingEconomics · " + country + " · " + indicator
                    : "TradingEconomics · " + symbol;
        }

        SourceCreateRequest request = new SourceCreateRequest(
                displayName.length() > 240 ? displayName.substring(0, 240) : displayName,
                "tradingeconomics",
                TradingEconomicsApiSupport.TE_BASE_URL,
                symbol.isBlank() ? "" : "/country/ticker/" + symbol,
                "GET",
                "none",
                null,
                Map.of("Accept", "application/json", "User-Agent", "banking-bi/1.0"),
                qp,
                toInteger(safe.get("refresh_interval_minutes"), 1440),
                toBoolean(safe.get("active"), true),
                displayName.length() > 240 ? displayName.substring(0, 240) : displayName,
                null);

        Map<String, Object> created = sourceService.createSource(request);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", created.get("id"));
        out.put("name", request.name());
        out.put("set_id", parsed.sidEcho());
        out.put("symbol", symbol.isBlank() ? null : symbol);
        out.put("country", country.isBlank() ? null : country);
        out.put("indicator", indicator.isBlank() ? null : indicator);
        return out;
    }

    private List<String> loadTeCountries() {
        long now = System.currentTimeMillis() / 1000L;
        if (!countryCache.isEmpty() && now - countryCacheTs < COUNTRY_CACHE_TTL_SEC) {
            return countryCache;
        }
        if (teApi.apiKey().isBlank()) {
            countryCache = FALLBACK_COUNTRIES;
            countryCacheTs = now;
            return countryCache;
        }
        try {
            List<Map<String, Object>> rows = teApi.getJsonList("/country/all/gdp", Map.of("f", "json"));
            java.util.Set<String> seen = new java.util.LinkedHashSet<>();
            List<String> countries = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                String c = TradingEconomicsApiSupport.ciGet(row, "Country");
                if (c.isBlank()) {
                    continue;
                }
                String lc = c.toLowerCase(Locale.ROOT);
                if (seen.contains(lc)) {
                    continue;
                }
                seen.add(lc);
                countries.add(c);
            }
            countries.sort(String.CASE_INSENSITIVE_ORDER);
            if (!countries.isEmpty()) {
                countryCache = countries;
                countryCacheTs = now;
                return countryCache;
            }
        } catch (Exception ignored) {
            // fallback below
        }
        countryCache = FALLBACK_COUNTRIES;
        countryCacheTs = now;
        return countryCache;
    }

    private Map<String, Object> buildCountryNodeLazy(String country) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("path", CAT_PATH + " > " + country);
        node.put("name", country);
        node.put("children", List.of());
        node.put("sets", List.of());
        node.put("te_country_lazy", true);
        return node;
    }

    private Map<String, Object> buildCountryNodeGrouped(String country) {
        String cslug = TradingEconomicsApiSupport.countrySlug(country);
        if (cslug.isBlank()) {
            cslug = "country";
        }
        CountryNodeCacheEntry cached = countryNodeCache.get(cslug);
        long now = System.currentTimeMillis() / 1000L;
        if (cached != null && now - cached.ts() < COUNTRY_NODE_CACHE_TTL_SEC) {
            return new LinkedHashMap<>(cached.node());
        }
        if (teApi.apiKey().isBlank()) {
            return buildCountryNodeLazy(country);
        }

        List<Map<String, Object>> merged = new ArrayList<>();
        java.util.Set<String> seenSig = new java.util.LinkedHashSet<>();
        String countryPath = TradingEconomicsApiSupport.urlEncode(country.toLowerCase(Locale.ROOT)).replace("+", "%20");

        List<Map<String, String>> batches = new ArrayList<>();
        batches.add(Map.of("f", "json"));
        for (String g : TE_GROUP_ORDER) {
            batches.add(Map.of("f", "json", "group", g.toLowerCase(Locale.ROOT)));
        }
        for (Map<String, String> params : batches) {
            try {
                List<Map<String, Object>> rows = teApi.getJsonList("/country/" + countryPath, params);
                for (Map<String, Object> row : rows) {
                    String cat = TradingEconomicsApiSupport.ciGet(row, "Category");
                    String sym = TradingEconomicsApiSupport.ciGet(row, "HistoricalDataSymbol").toUpperCase(Locale.ROOT);
                    String sig = (!sym.isBlank() || !cat.isBlank()) ? sym + "|" + cat.toLowerCase(Locale.ROOT) : "";
                    if (sig.isBlank() || seenSig.contains(sig)) {
                        continue;
                    }
                    seenSig.add(sig);
                    merged.add(row);
                }
            } catch (Exception ignored) {
                // continue batches
            }
        }

        if (merged.isEmpty()) {
            return buildFallbackCountryNode(country, cslug);
        }

        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : merged) {
            String group = normalizeGroupLabel(TradingEconomicsApiSupport.ciGet(row, "CategoryGroup"));
            groups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(row);
        }

        List<Map<String, Object>> children = new ArrayList<>();
        for (String g : TE_GROUP_ORDER) {
            List<Map<String, Object>> rows = groups.get(g);
            if (rows == null || rows.isEmpty()) {
                continue;
            }
            List<Map<String, Object>> sets = new ArrayList<>();
            for (Map<String, Object> item : rows) {
                String category = TradingEconomicsApiSupport.ciGet(item, "Category");
                if (category.isBlank()) {
                    continue;
                }
                String symbol = TradingEconomicsApiSupport.ciGet(item, "HistoricalDataSymbol").toUpperCase(Locale.ROOT);
                String indSlug = TradingEconomicsApiSupport.indicatorSlug(category);
                String setId = "__te_ci__" + cslug + "|" + indSlug;
                if (!symbol.isBlank()) {
                    setId = "TE|" + symbol;
                }
                Map<String, Object> qp = new LinkedHashMap<>();
                qp.put("country", country.toLowerCase(Locale.ROOT));
                qp.put("indicator", indSlug);
                qp.put("category", category);
                qp.put("category_group", g);
                qp.put("historical_symbol", symbol);

                Map<String, Object> set = new LinkedHashMap<>();
                set.put("set_id", setId);
                set.put("name", country + " · " + category);
                set.put("kind", "selection");
                set.put("query_params", qp);
                sets.add(set);
            }
            if (sets.isEmpty()) {
                continue;
            }
            Map<String, Object> child = new LinkedHashMap<>();
            child.put("path", CAT_PATH + " > " + country + " > " + g);
            child.put("name", g);
            child.put("children", List.of());
            child.put("sets", sets);
            children.add(child);
        }

        if (children.isEmpty()) {
            return buildCountryNodeLazy(country);
        }

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("path", CAT_PATH + " > " + country);
        node.put("name", country);
        node.put("children", children);
        node.put("sets", List.of());
        countryNodeCache.put(cslug, new CountryNodeCacheEntry(now, node));
        return node;
    }

    private Map<String, Object> buildFallbackCountryNode(String country, String cslug) {
        List<Map<String, Object>> sets = new ArrayList<>();
        for (Map.Entry<String, String> ind : COMMON_INDICATORS) {
            String setId = "__te_ci__" + cslug + "|" + ind.getKey();
            Map<String, Object> qp = new LinkedHashMap<>();
            qp.put("country", country.toLowerCase(Locale.ROOT));
            qp.put("indicator", ind.getKey());
            qp.put("category", ind.getValue());
            Map<String, Object> set = new LinkedHashMap<>();
            set.put("set_id", setId);
            set.put("name", country + " · " + ind.getValue());
            set.put("kind", "selection");
            set.put("query_params", qp);
            sets.add(set);
        }
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("path", CAT_PATH + " > " + country);
        node.put("name", country);
        node.put("children", List.of());
        node.put("sets", sets);
        node.put("te_group_fetch_fallback", true);
        return node;
    }

    private static String normalizeGroupLabel(String raw) {
        String t = stringOrBlank(raw);
        if (t.isBlank()) {
            return "Overview";
        }
        for (String g : TE_GROUP_ORDER) {
            if (g.equalsIgnoreCase(t)) {
                return g;
            }
        }
        return t;
    }

    private static ParsedTeSetId parseTeSetId(String setId) {
        String sid = stringOrBlank(setId);
        if (sid.isBlank()) {
            return new ParsedTeSetId(null, sid);
        }
        if (sid.toUpperCase(Locale.ROOT).startsWith("TE|")) {
            String[] parts = sid.split("\\|", 3);
            if (parts.length >= 2 && !parts[1].isBlank()) {
                return new ParsedTeSetId(parts[1].trim().toUpperCase(Locale.ROOT), sid);
            }
        }
        return new ParsedTeSetId(null, sid);
    }

    private static Map<String, Object> toStringObjectMap(Map<?, ?> input) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
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

    private static Integer toInteger(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ex) {
            return fallback;
        }
    }

    private static Boolean toBoolean(Object value, boolean fallback) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value == null) {
            return fallback;
        }
        String raw = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(raw)) {
            return true;
        }
        if ("false".equals(raw)) {
            return false;
        }
        return fallback;
    }

    private record CountryNodeCacheEntry(long ts, Map<String, Object> node) {}

    private record ParsedTeSetId(String symbol, String sidEcho) {}
}
