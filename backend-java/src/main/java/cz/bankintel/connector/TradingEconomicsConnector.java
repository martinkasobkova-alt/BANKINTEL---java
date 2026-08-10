package cz.bankintel.connector;

import cz.bankintel.sources.tradingeconomics.TradingEconomicsApiSupport;
import cz.bankintel.sources.tradingeconomics.TradingEconomicsApiSupport.TradingEconomicsHttpException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Konektor TradingEconomics — port {@code connectors/tradingeconomics.py}. */
@Component
@RequiredArgsConstructor
public class TradingEconomicsConnector implements BaseConnector {

    private static final int HISTORICAL_YEARS = 10;
    private static final Pattern WS = Pattern.compile("\\s+");

    private final TradingEconomicsApiSupport teApi;

    @Override
    public String sourceType() {
        return "tradingeconomics";
    }

    @Override
    public ConnectorFetchResult fetch(Map<String, Object> source) {
        if (teApi.apiKey().isBlank()) {
            return ConnectorFetchResult.error(
                    503,
                    Map.of(
                            "error", "TRADING_ECONOMICS_API_KEY is missing on backend",
                            "source", "TradingEconomics",
                            "detail_cs", "Na serveru není nastaven TRADING_ECONOMICS_API_KEY."),
                    source);
        }

        Map<String, Object> qp = queryParams(source);
        String symbol = firstNonBlank(
                        stringOrBlank(qp.get("historical_symbol")), stringOrBlank(source.get("tradingeconomics_symbol")))
                .toUpperCase(Locale.ROOT);
        String country = stringOrBlank(qp.get("country"));
        String category = stringOrBlank(qp.get("category"));
        String indicator = firstNonBlank(stringOrBlank(qp.get("indicator")), category);

        Map<String, String> histParams = historicalParams();

        try {
            if (!symbol.isBlank()) {
                Object data = teApi.getJson("/historical/ticker/" + encodePath(symbol), histParams);
                Map<String, Object> raw = new LinkedHashMap<>();
                raw.put("rows", TradingEconomicsApiSupport.asMapList(data));
                raw.put("mode", "historical_ticker");
                raw.put("symbol", symbol);
                return ConnectorFetchResult.ok(raw, source);
            }

            if (!country.isBlank() && !indicator.isBlank()) {
                String c = encodePath(country);
                List<String> histErrors = new ArrayList<>();
                for (String indVariant : indicatorSearchTerms(qp)) {
                    try {
                        Object data = teApi.getJson(
                                "/historical/country/" + c + "/indicator/" + encodePath(indVariant), histParams);
                        List<Map<String, Object>> rows = TradingEconomicsApiSupport.asMapList(data);
                        if (!rows.isEmpty()) {
                            Map<String, Object> raw = new LinkedHashMap<>();
                            raw.put("rows", rows);
                            raw.put("mode", "historical_country_indicator");
                            raw.put("country", country);
                            raw.put("indicator", indVariant);
                            return ConnectorFetchResult.ok(raw, source);
                        }
                    } catch (TradingEconomicsHttpException ex) {
                        histErrors.add(String.valueOf(ex.statusCode()));
                    }
                }

                List<Map<String, Object>> snapRows = teApi.countrySnapshotRows(country);
                List<Map<String, Object>> matched = snapRows.stream()
                        .filter(r -> snapshotMatch(r, indicator, category))
                        .toList();
                if (!matched.isEmpty()) {
                    String histSym = TradingEconomicsApiSupport.ciGet(matched.get(0), "HistoricalDataSymbol")
                            .toUpperCase(Locale.ROOT);
                    if (!histSym.isBlank()) {
                        try {
                            Object data = teApi.getJson("/historical/ticker/" + encodePath(histSym), histParams);
                            List<Map<String, Object>> rows = TradingEconomicsApiSupport.asMapList(data);
                            if (!rows.isEmpty()) {
                                Map<String, Object> raw = new LinkedHashMap<>();
                                raw.put("rows", rows);
                                raw.put("mode", "historical_ticker_from_snapshot");
                                raw.put("country", country);
                                raw.put("indicator", indicator);
                                raw.put("historical_symbol", histSym);
                                if (!histErrors.isEmpty()) {
                                    raw.put("warnings", List.of("historical_fallback:" + String.join(",", histErrors.subList(0, Math.min(3, histErrors.size())))));
                                }
                                return ConnectorFetchResult.ok(raw, source);
                            }
                        } catch (TradingEconomicsHttpException ignored) {
                            // fall through to snapshot
                        }
                    }
                    Map<String, Object> raw = new LinkedHashMap<>();
                    raw.put("rows", matched);
                    raw.put("mode", "snapshot_country_indicator");
                    raw.put("country", country);
                    raw.put("indicator", indicator);
                    if (!histErrors.isEmpty()) {
                        raw.put("warnings", List.of("historical_fallback:" + String.join(",", histErrors.subList(0, Math.min(3, histErrors.size())))));
                    }
                    return ConnectorFetchResult.ok(raw, source);
                }
                return ConnectorFetchResult.error(
                        404,
                        Map.of(
                                "error", "TradingEconomics indicator not found for selected country.",
                                "source", "TradingEconomics",
                                "detail_cs", "Pro zvolenou zemi a ukazatel nebyla nalezena dostupná data."),
                        source);
            }

            return ConnectorFetchResult.error(
                    400,
                    Map.of(
                            "error", "Missing identifier for TradingEconomics source.",
                            "source", "TradingEconomics",
                            "detail_cs", "Chybí ticker nebo kombinace country+indicator."),
                    source);
        } catch (TradingEconomicsHttpException ex) {
            int status = ex.statusCode();
            if (status == 401 || status == 403) {
                return ConnectorFetchResult.error(
                        401,
                        Map.of(
                                "source", "trading_economics",
                                "status", "auth_error",
                                "message", "Trading Economics API key was rejected or does not have access."),
                        source);
            }
            if (status == 429) {
                return ConnectorFetchResult.error(
                        429,
                        Map.of(
                                "source", "trading_economics",
                                "status", "rate_limited",
                                "message", "Trading Economics rate limit exceeded."),
                        source);
            }
            return ConnectorFetchResult.error(
                    status,
                    Map.of(
                            "error", "TradingEconomics HTTP " + status,
                            "source", "TradingEconomics",
                            "detail_cs", "TradingEconomics API vrátilo chybu. Zkuste to prosím později."),
                    source);
        } catch (Exception ex) {
            return ConnectorFetchResult.error(
                    502,
                    Map.of(
                            "error", ex.getMessage() != null ? ex.getMessage().substring(0, Math.min(ex.getMessage().length(), 280)) : "",
                            "source", "TradingEconomics",
                            "detail_cs", "Stažení dat z TradingEconomics se nepodařilo."),
                    source);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> parse(Object raw, Map<String, Object> source) {
        Object rowsObj = raw;
        if (raw instanceof Map<?, ?> map) {
            rowsObj = map.get("rows");
        }
        List<Map<String, Object>> rows = TradingEconomicsApiSupport.asMapList(rowsObj);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> item : rows) {
            String dt = normalizeDate(firstNonBlank(
                    TradingEconomicsApiSupport.ciGet(item, "DateTime", "Date", "LatestValueDate")));
            Object v = firstPresent(item, "Value", "Close", "LatestValue");
            if (dt.isBlank() || v == null) {
                continue;
            }
            try {
                double value = Double.parseDouble(String.valueOf(v));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("date", dt);
                row.put("value", value);
                row.put("amount", value);
                row.put("country", TradingEconomicsApiSupport.ciGet(item, "Country"));
                row.put("indicator", TradingEconomicsApiSupport.ciGet(item, "Category", "Indicator"));
                row.put("unit", TradingEconomicsApiSupport.ciGet(item, "Unit"));
                row.put("frequency", TradingEconomicsApiSupport.ciGet(item, "Frequency"));
                row.put("series_id", TradingEconomicsApiSupport.ciGet(item, "HistoricalDataSymbol"));
                row.put("source", "TradingEconomics");
                row.put("source_type", "tradingeconomics");
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("category_group", TradingEconomicsApiSupport.ciGet(item, "CategoryGroup"));
                metadata.put("title", TradingEconomicsApiSupport.ciGet(item, "Title"));
                row.put("metadata", metadata);
                out.add(row);
            } catch (NumberFormatException ignored) {
                // skip bad values
            }
        }
        return out;
    }

    private static Map<String, String> historicalParams() {
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusYears(HISTORICAL_YEARS);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("f", "json");
        params.put("initDate", start.toString());
        params.put("endDate", end.toString());
        return params;
    }

    private static List<String> indicatorSearchTerms(Map<String, Object> qp) {
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String raw : List.of(stringOrBlank(qp.get("category")), stringOrBlank(qp.get("indicator")))) {
            for (String term : indicatorVariants(raw)) {
                String key = term.trim().toLowerCase(Locale.ROOT);
                if (!key.isBlank() && seen.add(key)) {
                    out.add(term);
                }
            }
        }
        return out;
    }

    private static List<String> indicatorVariants(String indicator) {
        String base = normLabel(indicator);
        if (base.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        addUnique(out, stringOrBlank(indicator));
        addUnique(out, base);
        addUnique(out, titleCase(base));
        addUnique(out, base.replace(' ', '-'));
        return out;
    }

    private static boolean snapshotMatch(Map<String, Object> row, String indicator, String category) {
        String catLabel = normLabel(category);
        String cat = normLabel(TradingEconomicsApiSupport.ciGet(row, "Category", "Indicator"));
        if (!catLabel.isBlank() && catLabel.equals(cat)) {
            return true;
        }
        String want = normLabel(indicator);
        String title = normLabel(TradingEconomicsApiSupport.ciGet(row, "Title"));
        if (want.isBlank()) {
            return true;
        }
        return cat.contains(want) || want.contains(cat) || title.contains(want);
    }

    private static String normLabel(String val) {
        return WS.matcher(stringOrBlank(val).toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' '))
                .replaceAll(" ")
                .trim();
    }

    private static String titleCase(String base) {
        String[] parts = base.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    private static void addUnique(List<String> out, String val) {
        if (val != null && !val.isBlank() && !out.contains(val)) {
            out.add(val);
        }
    }

    private static String normalizeDate(String raw) {
        if (raw.isBlank()) {
            return "";
        }
        return raw.length() >= 10 ? raw.substring(0, 10) : raw;
    }

    private static Object firstPresent(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            Object val = row.get(key);
            if (val == null) {
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                        val = entry.getValue();
                        break;
                    }
                }
            }
            if (val != null && !String.valueOf(val).isBlank()) {
                return val;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> queryParams(Map<String, Object> source) {
        Object raw = source.get("query_params");
        if (raw instanceof Map<?, ?> map) {
            return ConnectorHttpSupport.stringMap(map);
        }
        Object config = source.get("connector_config");
        if (config instanceof Map<?, ?> cfg) {
            Object qp = cfg.get("query_params");
            if (qp instanceof Map<?, ?> map) {
                return ConnectorHttpSupport.stringMap(map);
            }
        }
        return Map.of();
    }

    private static String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
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
}
