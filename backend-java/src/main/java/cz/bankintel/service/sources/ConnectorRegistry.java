package cz.bankintel.service.sources;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ConnectorRegistry {

    private static final List<String> CONNECTOR_TYPES = List.of(
            "acea_mirror",
            "eba_mirror",
            "financial_markets_mirror",
            "entsoe_mirror",
            "gie_mirror",
            "arad",
            "yahoo",
            "yahoo_finance",
            "eurostat",
            "csu",
            "ecb",
            "ecb2",
            "eiopa",
            "fred",
            "alphavantage",
            "worldbank_pink_sheet",
            "world_bank_data360",
            "bis",
            "imf",
            "oecd",
            "tradingeconomics",
            "custom",
            "file_upload");

    private ConnectorRegistry() {}

    public static List<String> availableTypes() {
        return CONNECTOR_TYPES;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> buildConnectorConfig(
            Map<String, Object> headers,
            Map<String, Object> queryParams,
            Map<String, Object> credentials) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("headers", headers != null ? headers : Map.of());
        config.put("query_params", queryParams != null ? queryParams : Map.of());
        config.put("credentials", credentials != null ? credentials : Map.of());
        return config;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> headersFrom(Map<String, Object> connectorConfig) {
        if (connectorConfig == null) {
            return Map.of();
        }
        Object raw = connectorConfig.get("headers");
        return raw instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> queryParamsFrom(Map<String, Object> connectorConfig) {
        if (connectorConfig == null) {
            return Map.of();
        }
        Object raw = connectorConfig.get("query_params");
        return raw instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> credentialsFrom(Map<String, Object> connectorConfig) {
        if (connectorConfig == null) {
            return Map.of();
        }
        Object raw = connectorConfig.get("credentials");
        return raw instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
