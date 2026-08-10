package cz.bankintel.sources.imf;
import cz.bankintel.util.BankIntelEnvVars;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.connector.ConnectorParseSupport;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** IMF SDMX 3.0 HTTP helpers — port {@code imf_sdmx_client.py}. */
@Component
public class ImfApiSupport {

    public static final String IMF_SDMX_BASE_URL = "https://api.imf.org/external/sdmx/3.0";
    private static final String DATA_ACCEPT = "application/vnd.sdmx.data+json;version=2.0.0";

    private static final Map<String, String> ISO2_TO_ISO3 = Map.ofEntries(
            Map.entry("CZ", "CZE"), Map.entry("DE", "DEU"), Map.entry("PL", "POL"), Map.entry("SK", "SVK"),
            Map.entry("AT", "AUT"), Map.entry("FR", "FRA"), Map.entry("IT", "ITA"), Map.entry("ES", "ESP"),
            Map.entry("GB", "GBR"), Map.entry("US", "USA"), Map.entry("JP", "JPN"), Map.entry("CH", "CHE"),
            Map.entry("NO", "NOR"), Map.entry("SE", "SWE"), Map.entry("DK", "DNK"), Map.entry("FI", "FIN"),
            Map.entry("NL", "NLD"), Map.entry("BE", "BEL"), Map.entry("IE", "IRL"), Map.entry("PT", "PRT"),
            Map.entry("GR", "GRC"), Map.entry("HU", "HUN"), Map.entry("RO", "ROU"), Map.entry("BG", "BGR"),
            Map.entry("HR", "HRV"), Map.entry("SI", "SVN"), Map.entry("EE", "EST"), Map.entry("LV", "LVA"),
            Map.entry("LT", "LTU"), Map.entry("LU", "LUX"), Map.entry("MT", "MLT"), Map.entry("CY", "CYP"),
            Map.entry("CN", "CHN"), Map.entry("KR", "KOR"), Map.entry("IN", "IND"), Map.entry("ID", "IDN"),
            Map.entry("SG", "SGP"), Map.entry("CA", "CAN"), Map.entry("MX", "MEX"), Map.entry("BR", "BRA"),
            Map.entry("AR", "ARG"), Map.entry("CL", "CHL"), Map.entry("AU", "AUS"), Map.entry("NZ", "NZL"),
            Map.entry("ZA", "ZAF"));

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ImfApiSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public boolean configured() {
        return !apiKey().isBlank();
    }

    public String apiKey() {
        String key = BankIntelEnvVars.get("IMF_API_KEY");
        if (key == null || key.isBlank()) {
            key = BankIntelEnvVars.get("IMF_SUBSCRIPTION_KEY");
        }
        return key != null ? key.trim() : "";
    }

    public static String normalizeCountryCode(String code) {
        String c = code != null ? code.trim().toUpperCase(Locale.ROOT) : "";
        if (c.length() == 2) {
            return ISO2_TO_ISO3.getOrDefault(c, c);
        }
        return c;
    }

    public static String browseCountryCode(String code) {
        String normalized = normalizeCountryCode(code);
        for (Map.Entry<String, String> entry : ISO2_TO_ISO3.entrySet()) {
            if (entry.getValue().equals(normalized)) {
                return entry.getKey();
            }
        }
        return normalized;
    }

    public static String freqLabelCs(String freq) {
        return switch (stringOrBlank(freq).toUpperCase(Locale.ROOT)) {
            case "M" -> "Mesicne";
            case "Q" -> "Ctvrtletne";
            case "A" -> "Rocne";
            case "W" -> "Tydne";
            case "D" -> "Denne";
            default -> freq != null ? freq : "";
        };
    }

    public FetchSeriesResult fetchSeriesData(
            String agency,
            String flow,
            String version,
            String seriesKey,
            String startPeriod,
            String endPeriod) {
        Map<String, String> params = new LinkedHashMap<>();
        if (startPeriod != null && !startPeriod.isBlank()) {
            params.put("startPeriod", startPeriod);
        }
        if (endPeriod != null && !endPeriod.isBlank()) {
            params.put("endPeriod", endPeriod);
        }
        params.put("lastNObservations", "500");
        String path = "/data/dataflow/" + agency + "/" + flow + "/" + version + "/" + seriesKey;
        String url = IMF_SDMX_BASE_URL + path + "?" + toQuery(params);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(22))
                    .header("Accept", DATA_ACCEPT)
                    .header("User-Agent", "bankintel-bi/1.0")
                    .header("Ocp-Apim-Subscription-Key", apiKey())
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return new FetchSeriesResult(response.statusCode(), List.of(), Map.of("error", "HTTP " + response.statusCode()));
            }
            Map<String, Object> body = objectMapper.readValue(response.body(), new TypeReference<>() {});
            List<Map<String, Object>> rows = ConnectorParseSupport.parseImfSdmxDataJson(body);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("frekvence", frequencyFromSdmxKey(seriesKey));
            return new FetchSeriesResult(200, rows, meta);
        } catch (Exception ex) {
            return new FetchSeriesResult(0, List.of(), Map.of("error", ex.getMessage()));
        }
    }

    private static String frequencyFromSdmxKey(String sdmxKey) {
        String key = stringOrBlank(sdmxKey);
        if (key.isBlank()) {
            return "";
        }
        String tail = key.substring(key.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT);
        return List.of("A", "Q", "M", "D", "W", "S").contains(tail) ? tail : "";
    }

    private static String toQuery(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static String stringOrBlank(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    public record FetchSeriesResult(int statusCode, List<Map<String, Object>> rows, Map<String, Object> meta) {}
}
