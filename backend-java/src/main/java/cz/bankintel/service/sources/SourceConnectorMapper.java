package cz.bankintel.service.sources;

import cz.bankintel.connector.ConnectorFactory;
import cz.bankintel.domain.entity.SourceEntity;
import cz.bankintel.sources.bis.BisCatalogService;
import cz.bankintel.sources.ecb.EcbAvailabilityService;
import cz.bankintel.sources.ecb.EcbReference;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SourceConnectorMapper {

    private static final List<String> CONFIG_TOP_LEVEL_KEYS = List.of(
            "set_id",
            "fred_series_id",
            "csu_dataset_code",
            "csu_selection_code",
            "csu_full_path",
            "bis_catalog_set_id",
            "bis_dataflow",
            "bis_series_key",
            "_bis_query_context",
            "imf_flow",
            "imf_indicator",
            "imf_country",
            "imf_agency",
            "imf_version",
            "imf_sdmx_key",
            "imf_sdmx3",
            "oecd_sdmx_v2",
            "oecd_agency",
            "oecd_dataflow",
            "oecd_filter_expression",
            "ecb_flow",
            "ecb_series_key",
            "data360_database_id",
            "data360_indicator");

    private SourceConnectorMapper() {}

    public static Map<String, Object> toConnectorSource(SourceEntity source) {
        Map<String, Object> out = new LinkedHashMap<>();
        String sourceType = ConnectorFactory.normalizeSourceType(source.getSourceType());
        out.put("id", source.getId());
        out.put("name", source.getName());
        out.put("source_type", sourceType);
        out.put("base_url", source.getBaseUrl() != null ? source.getBaseUrl() : "");
        out.put("endpoint", source.getEndpoint() != null ? source.getEndpoint() : "");
        out.put("method", source.getMethod() != null ? source.getMethod() : "GET");
        out.put("auth_type", source.getAuthType() != null ? source.getAuthType() : "none");
        out.put("dataset_name", source.getDatasetName());

        Map<String, Object> config = source.getConnectorConfig() != null ? source.getConnectorConfig() : Map.of();
        Map<String, Object> headers = ConnectorRegistry.headersFrom(config);
        Map<String, Object> queryParams = new LinkedHashMap<>(ConnectorRegistry.queryParamsFrom(config));
        out.put("headers", headers);
        out.put("query_params", queryParams);
        out.put("credentials", ConnectorRegistry.credentialsFrom(config));

        if (config != null) {
            for (String key : CONFIG_TOP_LEVEL_KEYS) {
                Object value = config.get(key);
                if (value != null && !String.valueOf(value).isBlank()) {
                    out.put(key, value);
                }
            }
        }

        promoteQueryParam(out, queryParams, "set_id");
        promoteQueryParam(out, queryParams, "ecb_flow", "flowRef");
        promoteQueryParam(out, queryParams, "ecb_series_key", "seriesKey");
        promoteQueryParam(out, queryParams, "bis_dataflow", "flow");
        promoteQueryParam(out, queryParams, "bis_series_key", "key");
        promoteQueryParam(out, queryParams, "imf_flow");
        promoteQueryParam(out, queryParams, "imf_indicator");
        promoteQueryParam(out, queryParams, "imf_country", "country");
        promoteQueryParam(out, queryParams, "imf_sdmx_key");
        promoteQueryParam(out, queryParams, "DATABASE_ID", "data360_database_id");
        promoteQueryParam(out, queryParams, "INDICATOR", "data360_indicator");

        String setId = stringField(queryParams, "set_id");
        if (setId.isBlank()) {
            setId = stringField(queryParams, "series_id");
        }
        if (setId.isBlank()) {
            setId = stringField(out, "set_id");
        }
        if (!setId.isBlank()) {
            out.putIfAbsent("set_id", setId);
            if ("fred".equals(sourceType)) {
                out.putIfAbsent("fred_series_id", setId);
            }
        }

        enrichForSourceType(sourceType, source, out, queryParams);
        return out;
    }

    private static void enrichForSourceType(
            String sourceType, SourceEntity source, Map<String, Object> out, Map<String, Object> queryParams) {
        switch (sourceType) {
            case "bis" -> enrichBis(source, out);
            case "imf" -> enrichImf(source, out);
            case "oecd" -> enrichOecd(source, out);
            case "world_bank_data360" -> enrichData360(out, queryParams);
            case "ecb" -> enrichEcb(source, out, queryParams);
            case "eurostat" -> enrichEurostat(source, out);
            case "arad" -> enrichArad(out, queryParams);
            default -> {}
        }
    }

    private static void enrichBis(SourceEntity source, Map<String, Object> out) {
        String flow = stringField(out, "bis_dataflow");
        String key = stringField(out, "bis_series_key");
        if (flow.isBlank() || key.isBlank()) {
            final String flowBefore = flow;
            final String keyBefore = key;
            parseBisEndpoint(source.getEndpoint()).ifPresent(parsed -> {
                if (flowBefore.isBlank()) {
                    out.putIfAbsent("bis_dataflow", parsed.flow());
                }
                if (keyBefore.isBlank()) {
                    out.putIfAbsent("bis_series_key", parsed.key());
                }
            });
            flow = stringField(out, "bis_dataflow");
            key = stringField(out, "bis_series_key");
        }
        if (!flow.isBlank() && !key.isBlank()) {
            out.putIfAbsent("bis_catalog_set_id", BisCatalogService.composeCatalogSetId(flow, key));
            out.putIfAbsent("set_id", flow + "/" + key);
        }
        out.putIfAbsent("_bis_query_context", "sync");
    }

    private static void enrichImf(SourceEntity source, Map<String, Object> out) {
        String setId = stringField(out, "set_id");
        if (setId.isBlank()) {
            setId = imfSetIdFromEndpoint(source.getEndpoint());
            if (!setId.isBlank()) {
                out.put("set_id", setId);
            }
        }
        if (setId.startsWith("IMF|")) {
            String[] parts = setId.split("\\|");
            if (parts.length >= 5) {
                out.putIfAbsent("imf_agency", parts[1]);
                out.putIfAbsent("imf_flow", parts[2]);
                out.putIfAbsent("imf_version", parts[3]);
                out.putIfAbsent("imf_sdmx_key", parts[4]);
            }
        } else if (!setId.isBlank() && setId.contains("/")) {
            int slash = setId.indexOf('/');
            out.putIfAbsent("imf_flow", setId.substring(0, slash));
            out.putIfAbsent("imf_sdmx_key", setId.substring(slash + 1));
        }
        out.putIfAbsent("imf_sdmx3", true);
    }

    private static void enrichOecd(SourceEntity source, Map<String, Object> out) {
        String setId = stringField(out, "set_id");
        if (setId.isBlank()) {
            setId = oecdSetIdFromEndpoint(source.getEndpoint());
            if (!setId.isBlank()) {
                out.put("set_id", setId);
            }
        }
        if (setId.startsWith("SDMX2|")) {
            out.putIfAbsent("oecd_sdmx_v2", true);
            String[] parts = setId.split("\\|", 5);
            if (parts.length >= 5) {
                out.putIfAbsent("oecd_agency", parts[1]);
                out.putIfAbsent("oecd_dataflow", parts[2]);
                out.putIfAbsent("oecd_filter_expression", parts[4]);
            }
        }
    }

    private static void enrichData360(Map<String, Object> out, Map<String, Object> queryParams) {
        String databaseId = firstNonBlank(stringField(out, "data360_database_id"), stringField(queryParams, "DATABASE_ID"));
        String indicator = firstNonBlank(stringField(out, "data360_indicator"), stringField(queryParams, "INDICATOR"));
        if (!databaseId.isBlank()) {
            out.putIfAbsent("data360_database_id", databaseId);
            queryParams.putIfAbsent("DATABASE_ID", databaseId);
        }
        if (!indicator.isBlank()) {
            out.putIfAbsent("data360_indicator", indicator);
            queryParams.putIfAbsent("INDICATOR", indicator);
        }
        String setId = stringField(out, "set_id");
        if (setId.isBlank() && !databaseId.isBlank()) {
            out.putIfAbsent("set_id", indicator.isBlank() ? databaseId : databaseId + "|" + indicator);
        }
        queryParams.putIfAbsent("skip", "0");
        out.put("query_params", queryParams);
    }

    private static void enrichEcb(SourceEntity source, Map<String, Object> out, Map<String, Object> queryParams) {
        String flow = firstNonBlank(
                stringField(out, "ecb_flow"),
                stringField(queryParams, "ecb_flow"),
                stringField(queryParams, "flowRef"));
        String key = firstNonBlank(
                stringField(out, "ecb_series_key"),
                stringField(queryParams, "ecb_series_key"),
                stringField(queryParams, "seriesKey"));
        if (flow.isBlank() || key.isBlank()) {
            final String flowBefore = flow;
            final String keyBefore = key;
            parseEcbEndpoint(source.getEndpoint()).ifPresent(parsed -> {
                if (flowBefore.isBlank()) {
                    out.putIfAbsent("ecb_flow", parsed.flow());
                }
                if (keyBefore.isBlank()) {
                    out.putIfAbsent("ecb_series_key", parsed.key());
                }
            });
            flow = firstNonBlank(stringField(out, "ecb_flow"), stringField(queryParams, "ecb_flow"));
            key = firstNonBlank(stringField(out, "ecb_series_key"), stringField(queryParams, "ecb_series_key"));
        }
        if (!flow.isBlank()) {
            out.putIfAbsent("ecb_flow", flow);
            queryParams.putIfAbsent("ecb_flow", flow);
            queryParams.putIfAbsent("flowRef", flow);
        }
        if (!key.isBlank()) {
            out.putIfAbsent("ecb_series_key", key);
            queryParams.putIfAbsent("ecb_series_key", key);
            queryParams.putIfAbsent("seriesKey", key);
        }

        String setId = stringField(out, "set_id");
        if (setId.isBlank()) {
            EcbAvailabilityService.ParsedCurated curated = EcbAvailabilityService.parseCuratedSetId(
                    stringField(queryParams, "set_id"));
            if (curated != null) {
                setId = "ecb:" + curated.country() + ":" + curated.indicatorId();
            } else if (!flow.isBlank() && !key.isBlank()) {
                setId = flow + "/" + key;
            } else {
                EcbReference.Parsed ref = EcbReference.parseSetId(stringField(queryParams, "set_id"));
                if (ref != null && ref.validPreviewTarget()) {
                    setId = ref.flowRef() + "/" + ref.seriesKey();
                }
            }
            if (!setId.isBlank()) {
                out.put("set_id", setId);
            }
        }
        out.put("query_params", queryParams);
    }

    private static void enrichEurostat(SourceEntity source, Map<String, Object> out) {
        if (stringField(out, "set_id").isBlank()) {
            String code = eurostatCodeFromEndpoint(source.getEndpoint());
            if (!code.isBlank()) {
                out.put("set_id", code);
            }
        }
    }

    private static void enrichArad(Map<String, Object> out, Map<String, Object> queryParams) {
        String setId = stringField(out, "set_id");
        if (setId.isBlank()) {
            setId = stringField(queryParams, "set_id");
            if (!setId.isBlank()) {
                out.put("set_id", setId);
            }
        }
    }

    private static void promoteQueryParam(
            Map<String, Object> out, Map<String, Object> queryParams, String targetKey, String... aliasKeys) {
        if (!stringField(out, targetKey).isBlank()) {
            return;
        }
        List<String> keys = new java.util.ArrayList<>();
        keys.add(targetKey);
        if (aliasKeys != null) {
            keys.addAll(List.of(aliasKeys));
        }
        for (String key : keys) {
            String value = stringField(queryParams, key);
            if (!value.isBlank()) {
                out.put(targetKey, value);
                return;
            }
        }
    }

    private static java.util.Optional<FlowKey> parseBisEndpoint(String endpoint) {
        if (endpoint == null || !endpoint.startsWith("/data/")) {
            return java.util.Optional.empty();
        }
        String tail = endpoint.substring("/data/".length());
        String[] parts = tail.split("/");
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new FlowKey(parts[0], parts[1]));
    }

    private static java.util.Optional<FlowKey> parseEcbEndpoint(String endpoint) {
        if (endpoint == null) {
            return java.util.Optional.empty();
        }
        String marker = "/service/data/";
        int idx = endpoint.indexOf(marker);
        if (idx < 0) {
            return java.util.Optional.empty();
        }
        String tail = endpoint.substring(idx + marker.length());
        int slash = tail.indexOf('/');
        if (slash <= 0 || slash >= tail.length() - 1) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new FlowKey(tail.substring(0, slash), tail.substring(slash + 1)));
    }

    private static String imfSetIdFromEndpoint(String endpoint) {
        if (endpoint == null) {
            return "";
        }
        String marker = "/CompactData/";
        int idx = endpoint.indexOf(marker);
        if (idx < 0) {
            marker = "/data/dataflow/";
            idx = endpoint.indexOf(marker);
            if (idx < 0) {
                return "";
            }
            String tail = endpoint.substring(idx + marker.length());
            String[] parts = tail.split("/");
            if (parts.length >= 4) {
                return "IMF|" + parts[0] + "|" + parts[1] + "|" + parts[2] + "|" + parts[3];
            }
            return "";
        }
        return endpoint.substring(idx + "/CompactData/".length());
    }

    private static String oecdSetIdFromEndpoint(String endpoint) {
        if (endpoint == null) {
            return "";
        }
        String marker = "/SDMX-JSON/data/";
        int idx = endpoint.indexOf(marker);
        if (idx < 0) {
            if (endpoint.contains("/data/dataflow/")) {
                return "";
            }
            return "";
        }
        String tail = endpoint.substring(idx + marker.length());
        if (tail.endsWith("/all")) {
            tail = tail.substring(0, tail.length() - 4);
        }
        int slash = tail.indexOf('/');
        if (slash <= 0 || slash >= tail.length() - 1) {
            return "";
        }
        return tail.substring(0, slash) + "/" + tail.substring(slash + 1);
    }

    private static String eurostatCodeFromEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return "";
        }
        String code = endpoint.startsWith("/") ? endpoint.substring(1) : endpoint;
        int q = code.indexOf('?');
        return q >= 0 ? code.substring(0, q) : code;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String stringField(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private record FlowKey(String flow, String key) {}
}
