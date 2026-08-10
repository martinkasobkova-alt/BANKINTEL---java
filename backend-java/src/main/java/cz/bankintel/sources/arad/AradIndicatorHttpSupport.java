package cz.bankintel.sources.arad;
import cz.bankintel.util.BankIntelEnvVars;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AradIndicatorHttpSupport {

    private static final URI DEFAULT_BASE = URI.create("https://www.cnb.cz/aradb/api/v1");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public AradIndicatorHttpSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    public List<Map<String, Object>> fetchIndicators(String setId) throws IOException, InterruptedException {
        String apiKey = resolveApiKey();
        if (apiKey.isBlank()) {
            return List.of();
        }
        String query = "set_id=" + urlEncode(setId) + "&lang=CS&api_key=" + urlEncode(apiKey);
        URI uri = URI.create(DEFAULT_BASE + "/indicators?" + query);
        HttpRequest request =
                HttpRequest.newBuilder().uri(uri).timeout(Duration.ofSeconds(120)).GET().build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("ARAD HTTP " + response.statusCode());
        }
        String contentType = response.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
        String body = decodeBody(response.body(), contentType);
        if (contentType.contains("json")) {
            Map<String, Object> payload = objectMapper.readValue(body, new TypeReference<>() {});
            Object data = payload.get("data");
            if (data instanceof List<?> list) {
                return castRows(list);
            }
        }
        return parseCsv(body);
    }

    public boolean apiKeyConfigured() {
        return !resolveApiKey().isBlank();
    }

    public static boolean apiKeyConfiguredStatic() {
        return !resolveApiKey().isBlank();
    }

    static String resolveApiKey() {
        String key = BankIntelEnvVars.get("ARAD_API_KEY");
        return key != null ? key.trim() : "";
    }

    static Map<String, Object> serializeIndicator(Map<String, Object> ind) {
        String iid = stringOrBlank(ind.get("indicator_id"));
        if (iid.isBlank()) {
            return null;
        }
        String fullName = firstNonBlank(stringOrBlank(ind.get("indicator_name")), stringOrBlank(ind.get("name")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("indicator_id", iid);
        out.put("name", shortIndicatorName(fullName));
        out.put("full_name", fullName);
        out.put("frequency_code", stringOrBlank(ind.get("frequency_code")));
        out.put("frequency_name", stringOrBlank(ind.get("frequency_name")));
        return out;
    }

    private static List<Map<String, Object>> castRows(List<?> list) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
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

    private static List<Map<String, Object>> parseCsv(String text) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return out;
            }
            String[] headers = headerLine.split(";");
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(";", -1);
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < headers.length && i < parts.length; i++) {
                    row.put(headers[i].trim(), parts[i].trim());
                }
                out.add(row);
            }
        } catch (IOException ex) {
            return List.of();
        }
        return out;
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static String decodeBody(byte[] bytes, String contentType) {
        Charset declared = charsetFromContentType(contentType);
        if (declared != null) {
            return new String(bytes, declared);
        }
        try {
            return decodeStrict(bytes, StandardCharsets.UTF_8);
        } catch (CharacterCodingException ignored) {
            return new String(bytes, Charset.forName("windows-1250"));
        }
    }

    private static Charset charsetFromContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        for (String part : contentType.split(";")) {
            String trimmed = part.trim();
            if (!trimmed.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                continue;
            }
            String name = trimmed.substring("charset=".length()).trim().replace("\"", "");
            try {
                return Charset.forName(name);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static String decodeStrict(byte[] bytes, Charset charset) throws CharacterCodingException {
        return charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    private static String stringOrBlank(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).trim();
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1).trim();
        }
        return text.replace("\"\"", "\"");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String shortIndicatorName(String fullName) {
        String value = stringOrBlank(fullName);
        int comma = value.lastIndexOf(',');
        if (comma >= 0 && comma + 1 < value.length()) {
            return value.substring(comma + 1).trim();
        }
        int colon = value.lastIndexOf(':');
        if (colon >= 0 && colon + 1 < value.length()) {
            return value.substring(colon + 1).trim();
        }
        return value;
    }
}
