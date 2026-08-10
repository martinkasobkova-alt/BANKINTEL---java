package cz.bankintel.connector;

import cz.bankintel.search.CatalogCountryIso3Registry;
import cz.bankintel.search.CatalogGeoIntent;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Konektor World Bank Data360 — paginované JSON API.
 *
 * <p>Python originál: {@code connectors/world_bank_data360.py}.
 */
@Component
@RequiredArgsConstructor
public class Data360Connector implements BaseConnector {

    private static final int PAGE_SIZE = 1000;
    private static final int MAX_PAGES = 50;

    private final ConnectorHttpSupport http;

    @Override
    public String sourceType() {
        return "world_bank_data360";
    }

    @Override
    public ConnectorFetchResult fetch(Map<String, Object> source) {
        Map<String, String> params = normalizeParams(source);
        if (params.get("DATABASE_ID") == null || params.get("DATABASE_ID").isBlank()) {
            return ConnectorFetchResult.error(
                    400,
                    errorMap(new Data360ErrorClassifier.Classification(
                            Data360ErrorClassifier.HTTP_4XX, false, 400, "", ""),
                            "Pro World Bank Data360 je povinný DATABASE_ID."),
                    source);
        }

        String url = ConnectorHttpSupport.buildUrl(string(source, "base_url"), string(source, "endpoint"));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("User-Agent", "banking-bi/1.0");
        mergeHeaders(headers, source.get("headers"));

        try {
            Map<String, Object> merged = fetchAllPages(url, headers, params);
            return ConnectorFetchResult.ok(merged, source);
        } catch (Data360HttpStatusException ex) {
            Data360ErrorClassifier.Classification classification =
                    Data360ErrorClassifier.classifyStatus(ex.status, ex.parsedBody);
            return ConnectorFetchResult.error(
                    ex.status, errorMap(classification, Data360ErrorClassifier.detailCsFor(classification.category())), source);
        } catch (Exception ex) {
            Data360ErrorClassifier.Classification classification = Data360ErrorClassifier.classifyException(ex);
            return ConnectorFetchResult.error(
                    classification.httpStatus(),
                    errorMap(classification, Data360ErrorClassifier.detailCsFor(classification.category())),
                    source);
        }
    }

    /**
     * Additive to the pre-existing {@code error}/{@code detail_cs} keys (see the Data360 error
     * classification audit) - {@code error_category}/{@code retryable}/{@code http_status}/{@code
     * upstream_code}/{@code upstream_message} are new fields carried unchanged through {@code
     * PreviewResponseBuilder.buildError}'s existing {@code out.put("error", errorBody)} line, so no
     * change to that shared, cross-connector builder is needed. {@code upstream_message} is already
     * truncated and contains only World Bank Data360's own generic API text (see {@link
     * Data360ErrorClassifier}), never request headers or credentials.
     */
    private static Map<String, Object> errorMap(Data360ErrorClassifier.Classification classification, String detailCs) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", classification.category());
        out.put("detail_cs", detailCs);
        out.put("error_category", classification.category());
        out.put("retryable", classification.retryable());
        out.put("http_status", classification.httpStatus());
        if (!classification.upstreamCode().isBlank()) {
            out.put("upstream_code", classification.upstreamCode());
        }
        if (!classification.upstreamMessage().isBlank()) {
            out.put("upstream_message", classification.upstreamMessage());
        }
        return out;
    }

    /** Carries the real HTTP status and (if parseable) response body out of {@link #fetchAllPages}. */
    private static final class Data360HttpStatusException extends Exception {
        private final int status;
        private final Map<String, Object> parsedBody;

        Data360HttpStatusException(int status, Map<String, Object> parsedBody) {
            super("Data360 API returned HTTP " + status);
            this.status = status;
            this.parsedBody = parsedBody;
        }
    }

    @Override
    public List<Map<String, Object>> parse(Object raw, Map<String, Object> source) {
        if (!(raw instanceof Map<?, ?> map)) {
            return List.of();
        }
        return ConnectorParseSupport.parseData360Rows(ConnectorHttpSupport.stringMap(map));
    }

    private Map<String, Object> fetchAllPages(String url, Map<String, String> headers, Map<String, String> params)
            throws Exception {
        List<Object> mergedRows = new ArrayList<>();
        Map<String, Object> meta = new LinkedHashMap<>();
        int skip = 0;
        boolean relaxedUsed = false;
        Map<String, String> activeParams = new LinkedHashMap<>(params);

        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, String> pageParams = new LinkedHashMap<>(activeParams);
            pageParams.put("skip", String.valueOf(skip));
            HttpResponse<String> response = http.get(url, headers, toObjectQuery(pageParams), Duration.ofSeconds(60));
            if (response.statusCode() != 200) {
                if (!mergedRows.isEmpty()) {
                    meta.put("value", mergedRows);
                    return meta;
                }
                throw new Data360HttpStatusException(response.statusCode(), tryParseJson(response.body()));
            }

            Map<String, Object> body = http.parseJson(response.body());
            for (Map.Entry<String, Object> entry : body.entrySet()) {
                if (!"value".equals(entry.getKey()) && !"Value".equals(entry.getKey())) {
                    meta.put(entry.getKey(), entry.getValue());
                }
            }

            List<Object> pageRows = extractValueRows(body);
            if (pageRows.isEmpty()) {
                if (skip == 0 && !relaxedUsed) {
                    Map<String, String> relaxed = fallbackParamsForEmpty(activeParams);
                    if (!relaxed.equals(activeParams)) {
                        relaxedUsed = true;
                        activeParams = relaxed;
                        continue;
                    }
                }
                break;
            }

            mergedRows.addAll(pageRows);
            if (pageRows.size() < PAGE_SIZE) {
                break;
            }
            skip += PAGE_SIZE;
        }

        Map<String, Object> out = new LinkedHashMap<>(meta);
        out.put("value", mergedRows);
        if (relaxedUsed) {
            out.put("data360_relaxed_params_used", true);
            out.put("data360_relaxed_params", activeParams);
        }
        return out;
    }

    /** Best-effort parse for an error response body - may not be JSON at all (e.g. an HTML proxy error page). */
    private Map<String, Object> tryParseJson(String body) {
        try {
            return http.parseJson(body);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static List<Object> extractValueRows(Map<String, Object> body) {
        Object vals = body.get("value");
        if (vals == null) {
            vals = body.get("Value");
        }
        if (vals instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return List.of();
    }

    private static Map<String, String> fallbackParamsForEmpty(Map<String, String> params) {
        Set<String> keep =
                Set.of("DATABASE_ID", "INDICATOR", "REF_AREA", "FREQ", "timePeriodFrom", "timePeriodTo", "skip");
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (keep.contains(entry.getKey()) && entry.getValue() != null && !entry.getValue().isBlank()) {
                out.put(entry.getKey(), entry.getValue().trim());
            }
        }
        out.putIfAbsent("skip", "0");
        return out;
    }

    private static Map<String, String> normalizeParams(Map<String, Object> source) {
        Map<String, Object> raw = source.get("query_params") instanceof Map<?, ?> m ? ConnectorHttpSupport.stringMap(m) : Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String ck = canonQueryKey(String.valueOf(entry.getKey()));
            if (!ck.isBlank()) {
                String val = paramValue(ck, entry.getValue());
                if (val.isBlank()) {
                    continue;
                }
                out.put(ck, val);
            }
        }
        out.putIfAbsent("skip", "0");
        return out;
    }

    private static String paramValue(String canonicalKey, Object raw) {
        if ("REF_AREA".equals(canonicalKey)) {
            return String.join(",", geoSelectionValues(raw).stream()
                    .map(Data360Connector::toData360Iso3)
                    .filter(s -> !s.isBlank())
                    .distinct()
                    .toList());
        }
        if (raw instanceof Iterable<?> iterable) {
            List<String> vals = new ArrayList<>();
            for (Object item : iterable) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    vals.add(String.valueOf(item).trim());
                }
            }
            return String.join(",", vals);
        }
        return String.valueOf(raw).trim();
    }

    private static List<String> geoSelectionValues(Object raw) {
        List<String> out = new ArrayList<>();
        collectGeoSelectionValues(raw, out);
        return out;
    }

    private static void collectGeoSelectionValues(Object raw, List<String> out) {
        if (raw == null) {
            return;
        }
        if (raw instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                collectGeoSelectionValues(item, out);
            }
            return;
        }
        String text = String.valueOf(raw).trim();
        if (text.isBlank()) {
            return;
        }
        text = text.replace("[", "").replace("]", "");
        for (String part : text.split("[,+]")) {
            String code = part.trim().toUpperCase(Locale.ROOT);
            if (!code.isBlank()) {
                out.add(code);
            }
        }
    }

    private static String toData360Iso3(String raw) {
        String iso2 = CatalogGeoIntent.resolveTerritoryToCountryCode(raw);
        if (!iso2.isBlank()) {
            return CatalogCountryIso3Registry.iso3For(iso2);
        }
        String upper = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        return CatalogCountryIso3Registry.isKnownIso3(upper) ? upper : "";
    }

    private static String canonQueryKey(String key) {
        String ks = key != null ? key.trim() : "";
        if (ks.isBlank()) {
            return "";
        }
        String low = ks.toLowerCase(Locale.ROOT).replace("-", "_");
        return switch (low) {
            case "database_id" -> "DATABASE_ID";
            case "indicator" -> "INDICATOR";
            case "skip" -> "skip";
            case "timeperiodfrom" -> "timePeriodFrom";
            case "timeperiodto" -> "timePeriodTo";
            case "ref_area", "refarea" -> "REF_AREA";
            case "sex" -> "SEX";
            case "age" -> "AGE";
            case "urbanisation" -> "URBANISATION";
            case "comp_breakdown_1" -> "COMP_BREAKDOWN_1";
            case "comp_breakdown_2" -> "COMP_BREAKDOWN_2";
            case "comp_breakdown_3" -> "COMP_BREAKDOWN_3";
            case "time_period" -> "TIME_PERIOD";
            case "freq" -> "FREQ";
            case "unit_measure" -> "UNIT_MEASURE";
            case "unit_type" -> "UNIT_TYPE";
            case "unit_mult" -> "UNIT_MULT";
            default -> ks.contains("_") || ks.equals(ks.toUpperCase(Locale.ROOT)) ? ks.toUpperCase(Locale.ROOT) : ks;
        };
    }

    private static Map<String, Object> toObjectQuery(Map<String, String> params) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.putAll(params);
        return out;
    }

    private static void mergeHeaders(Map<String, String> headers, Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                headers.putIfAbsent(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
