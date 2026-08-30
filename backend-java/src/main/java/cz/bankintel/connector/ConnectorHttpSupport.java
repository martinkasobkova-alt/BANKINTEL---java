package cz.bankintel.connector;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.springframework.stereotype.Component;

/**
 * Sdílené HTTP a parsovací utility pro konektory (GET/POST, CSV, JSON).
 *
 * <p>Charset handling port {@code connectors/arad.py:_apply_arad_response_encoding} — fetch bytes first,
 * decode once from Content-Type / BOM (cp1250 for ČNB/ČSÚ).
 */
@Component
public class ConnectorHttpSupport {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ConnectorHttpSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    public HttpResponse<String> get(String url, Map<String, String> headers, Map<String, Object> queryParams, Duration timeout)
            throws IOException, InterruptedException {
        URI uri = buildUri(url, queryParams);
        HttpRequest.Builder builder =
                HttpRequest.newBuilder().uri(uri).timeout(timeout != null ? timeout : Duration.ofSeconds(60)).GET();
        headers.forEach(builder::header);
        return httpClient.send(builder.build(), decodedStringBody());
    }

    public HttpResponse<String> postJson(
            String url, Map<String, String> headers, Map<String, Object> queryParams, String jsonBody, Duration timeout)
            throws IOException, InterruptedException {
        URI uri = buildUri(url, queryParams);
        HttpRequest.Builder builder =
                HttpRequest.newBuilder()
                        .uri(uri)
                        .timeout(timeout != null ? timeout : Duration.ofSeconds(120))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody != null ? jsonBody : "{}"))
                        .header("Accept", "application/json");
        headers.forEach(builder::header);
        return httpClient.send(builder.build(), decodedStringBody());
    }

    /**
     * Genuinely async, cancellable GET: the returned future IS the underlying HTTP exchange
     * ({@code HttpClient.sendAsync}), not a wrapper completed independently of it. Cancelling this
     * future (e.g. {@code future.cancel(true)}) aborts the exchange — unlike the synchronous
     * {@link #get} path, where a timeout imposed by a caller cannot stop the blocking
     * {@code httpClient.send(...)} call underneath it (see
     * {@code docs/archive/search-v2-preview-cancellation-investigation.md} section 7).
     */
    public CompletableFuture<HttpResponse<String>> getAsync(
            String url, Map<String, String> headers, Map<String, Object> queryParams, Duration timeout) {
        URI uri = buildUri(url, queryParams);
        HttpRequest.Builder builder =
                HttpRequest.newBuilder().uri(uri).timeout(timeout != null ? timeout : Duration.ofSeconds(60)).GET();
        headers.forEach(builder::header);
        return httpClient.sendAsync(builder.build(), decodedStringBody());
    }

    static URI buildUri(String url, Map<String, Object> queryParams) {
        String base = url == null ? "" : url;
        String query = toQuery(queryParams != null ? queryParams : Map.of());
        if (query.isBlank()) {
            return URI.create(base);
        }
        String separator = base.contains("?") ? "&" : "?";
        return URI.create(base + separator + query);
    }

    private static HttpResponse.BodyHandler<String> decodedStringBody() {
        return responseInfo -> HttpResponse.BodySubscribers.mapping(
                HttpResponse.BodySubscribers.ofByteArray(),
                bytes -> decodeResponseBody(
                        responseInfo.headers().firstValue("content-type").orElse(""), bytes));
    }

    /** Decode HTTP body bytes using Content-Type charset / UTF-8 BOM. Ref: connectors/arad.py:29-39 */
    public static String decodeResponseBody(String contentType, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        Charset charset = detectCharsetStatic(contentType, null);
        return new String(bytes, charset);
    }

    public Map<String, Object> parseJson(String body) throws IOException {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(body, new TypeReference<>() {});
    }

    public List<Map<String, Object>> parseCsv(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String sample = text.length() > 4096 ? text.substring(0, 4096) : text;
        char delim = sample.chars().filter(ch -> ch == ';').count() > sample.chars().filter(ch -> ch == ',').count()
                ? ';'
                : ',';
        String[] lines = text.split("\\R");
        if (lines.length < 2) {
            return List.of();
        }
        String[] headers = splitCsvLine(lines[0], delim);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            String[] cells = splitCsvLine(lines[i], delim);
            Map<String, Object> row = new LinkedHashMap<>();
            for (int c = 0; c < headers.length && c < cells.length; c++) {
                String key = headers[c] != null ? headers[c].trim() : "";
                if (!key.isBlank()) {
                    row.put(key, cells[c] != null ? cells[c].trim() : "");
                }
            }
            if (!row.isEmpty()) {
                rows.add(row);
            }
        }
        return rows;
    }

    public Charset detectCharset(String contentType, String bodySample) {
        return detectCharsetStatic(contentType, bodySample);
    }

    private static Charset detectCharsetStatic(String contentType, String bodySample) {
        if (contentType != null) {
            String lower = contentType.toLowerCase();
            if (lower.contains("1250") || lower.contains("win1250") || lower.contains("cp1250")) {
                return Charset.forName("windows-1250");
            }
            int idx = lower.indexOf("charset=");
            if (idx >= 0) {
                String charset = lower.substring(idx + 8).split(";")[0].trim();
                if (!charset.isBlank() && !charset.equals("utf-8") && !charset.equals("utf8")) {
                    try {
                        return Charset.forName(charset);
                    } catch (Exception ignored) {
                        // fall through
                    }
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    public static String buildUrl(String baseUrl, String endpoint) {
        String base = baseUrl != null ? baseUrl.replaceAll("/+$", "") : "";
        String ep = endpoint != null ? endpoint.replaceAll("^/+", "") : "";
        if (ep.isBlank()) {
            return base;
        }
        return base + "/" + ep;
    }

    public static Map<String, Object> stringMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    private static String toQuery(Map<String, Object> params) {
        List<String> pairs = new ArrayList<>();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof Iterable<?> iterable) {
                for (Object item : iterable) {
                    if (item != null && !String.valueOf(item).isBlank()) {
                        pairs.add(encode(entry.getKey()) + "=" + encode(String.valueOf(item)));
                    }
                }
            } else if (!String.valueOf(value).isBlank()) {
                pairs.add(encode(entry.getKey()) + "=" + encode(String.valueOf(value)));
            }
        }
        return String.join("&", pairs);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String[] splitCsvLine(String line, char delim) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                inQuotes = !inQuotes;
            } else if (ch == delim && !inQuotes) {
                cells.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        cells.add(current.toString());
        return cells.toArray(String[]::new);
    }
}
