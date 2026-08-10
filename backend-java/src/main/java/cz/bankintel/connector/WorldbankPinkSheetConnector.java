package cz.bankintel.connector;

import cz.bankintel.sources.commodities.WorldbankCommoditiesService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Konektor World Bank Pink Sheet / CMO forecast — port {@code connectors/worldbank_pink_sheet.py}. */
@Component
@RequiredArgsConstructor
public class WorldbankPinkSheetConnector implements BaseConnector {

    private final WorldbankCommoditiesService commoditiesService;

    @Override
    public String sourceType() {
        return "worldbank_pink_sheet";
    }

    @Override
    public ConnectorFetchResult fetch(Map<String, Object> source) {
        Map<String, Object> qp = queryParams(source);
        String kind = stringOrBlank(qp.get("kind"));
        if (kind.isBlank()) {
            kind = "actual";
        }
        kind = kind.toLowerCase(Locale.ROOT);
        String setId = firstNonBlank(
                stringOrBlank(qp.get("pink_sheet_code")),
                stringOrBlank(qp.get("commodity_code")),
                stringOrBlank(source.get("pink_sheet_code")),
                stringOrBlank(source.get("set_id")));

        if ("forecast".equals(kind) || setId.startsWith("FCST|")) {
            Map<String, Object> item = commoditiesService.forecastItemById(setId);
            if (item == null) {
                return ConnectorFetchResult.error(
                        404,
                        Map.of("error", "CMO forecast řada '" + setId + "' nebyla nalezena v cache."),
                        source);
            }
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("kind", "forecast");
            raw.put("set_id", setId);
            raw.put("item", item);
            return ConnectorFetchResult.ok(raw, source);
        }

        Map<String, Object> meta = commoditiesService.pinkSheetSeriesById(setId);
        if (meta == null) {
            return ConnectorFetchResult.error(
                    404, Map.of("error", "Pink Sheet řada '" + setId + "' nebyla nalezena v cache."), source);
        }
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("kind", "actual");
        raw.put("set_id", setId);
        raw.put("meta", meta);
        return ConnectorFetchResult.ok(raw, source);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> parse(Object raw, Map<String, Object> source) {
        if (!(raw instanceof Map<?, ?> map)) {
            return List.of();
        }
        Map<String, Object> payload = ConnectorHttpSupport.stringMap(map);
        String kind = stringOrBlank(payload.get("kind"));
        if (kind.isBlank()) {
            kind = "actual";
        }
        String sid = stringOrBlank(payload.get("set_id"));
        if ("forecast".equals(kind)) {
            return commoditiesService.recordsFromForecast(sid);
        }
        return commoditiesService.recordsFromPinkSheet(sid);
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
