package cz.bankintel.sources.ecb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EcbApiClient {

    private static final Pattern YEAR_Q = Pattern.compile("(\\d{4})-Q([1-4])", Pattern.CASE_INSENSITIVE);
    private static final Pattern YEAR_MONTH = Pattern.compile("(\\d{4})-(\\d{2})");
    private static final Pattern YEAR_ONLY = Pattern.compile("\\d{4}");

    /**
     * Kolik pozorování si vyžádat, když dotaz neurčuje časové rozpětí. Dřív 120, což u denní
     * řady znamená poslední čtyři měsíce. Naměřeno na EXR/D.USD.EUR.SP00.A od 1999:
     * 120 -> 120 řádků / 0,40 s, 20 000 -> 7 144 řádků / 0,92 s.
     */
    private static final String FULL_HISTORY_OBSERVATIONS = "20000";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).build();

    public List<Map<String, Object>> fetchCuratedSeries(
            EcbCuratedCatalog catalog,
            EcbAvailabilityService availability,
            String indicatorId,
            String country,
            String start,
            String end)
            throws Exception {
        String c = catalog.validateCountryCode(country);
        String indId = indicatorId != null ? indicatorId.trim() : "";
        if (!availability.isPairAvailable(c, indId)) {
            throw new IllegalStateException("Tento ukazatel není pro vybranou zemi v katalogu dostupný.");
        }
        Map<String, Object> ind = catalog.indicatorById(indId);
        if (ind == null) {
            throw new IllegalArgumentException("Neznámý ukazatel '" + indId + "'.");
        }
        String deriveFrom = stringOrBlank(ind.get("derive_yoy_from"));
        Map<String, Object> targetInd = deriveFrom.isBlank() ? ind : catalog.indicatorById(deriveFrom);
        if (targetInd == null) {
            throw new IllegalStateException("Chybí základní ukazatel pro výpočet meziroční změny.");
        }
        EcbCuratedCatalog.SdmxKey sdmx = catalog.sdmxKeyForCountry(targetInd, c);
        Map<String, Object> data = fetchEcbJson(sdmx.flow(), sdmx.key(), start, end);
        List<Map<String, Object>> series = parseEcbJsonTimeseries(data);
        if (!deriveFrom.isBlank()) {
            series = computeYoyFromIndex(series);
            if (series.isEmpty()) {
                throw new IllegalStateException(
                        "Z indexové řady se nepodařilo spočítat meziroční změnu (málo historických období).");
            }
        }
        return series;
    }

    public Map<String, Object> fetchEcbJson(String flow, String key, String start, String end) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("format", "jsondata");
        if (start != null && !start.isBlank()) {
            params.put("startPeriod", start.trim());
        }
        if (end != null && !end.isBlank()) {
            params.put("endPeriod", end.trim());
        }
        if ((start == null || start.isBlank()) && (end == null || end.isBlank())) {
            params.put("lastNObservations", FULL_HISTORY_OBSERVATIONS);
        }
        String url = buildUrl(flow, key, params);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(35))
                .header("Accept", "application/json")
                .header("User-Agent", "banking-bi/1.0")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new EcbUpstreamException(
                    response.statusCode(),
                    ecbErrorDetailCz(flow, key, response.statusCode(), response.body()));
        }
        return objectMapper.readValue(response.body(), new TypeReference<>() {});
    }

    public String fetchEcbCsv(String flow, String key, Map<String, String> extraParams) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("format", "csvdata");
        params.put("detail", "dataonly");
        if (extraParams != null) {
            params.putAll(extraParams);
        }
        if (!params.containsKey("startPeriod")
                && !params.containsKey("endPeriod")
                && !params.containsKey("lastNObservations")
                && !params.containsKey("firstNObservations")) {
            params.put("lastNObservations", FULL_HISTORY_OBSERVATIONS);
        }
        String url = buildUrl(flow, key, params);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(35))
                .header("Accept", "text/csv")
                .header("User-Agent", "banking-bi/1.0")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new EcbUpstreamException(
                    response.statusCode(),
                    ecbErrorDetailCz(flow, key, response.statusCode(), response.body()));
        }
        String body = response.body() != null ? response.body() : "";
        if (body.isBlank()) {
            throw new EcbUpstreamException(502, "ECB vrátilo prázdnou odpověď pro " + flow + "/" + key + ".");
        }
        return body;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> parseEcbJsonTimeseries(Map<String, Object> data) {
        try {
            Map<String, Object> structure = (Map<String, Object>) data.get("structure");
            Map<String, Object> dimensions = (Map<String, Object>) structure.get("dimensions");
            List<Map<String, Object>> obsDims = (List<Map<String, Object>>) dimensions.get("observation");
            Map<String, Object> timeDim = null;
            for (Map<String, Object> dim : obsDims) {
                String id = stringOrBlank(dim.get("id"));
                if ("TIME_PERIOD".equals(id) || "TIME".equals(id)) {
                    timeDim = dim;
                    break;
                }
            }
            if (timeDim == null) {
                return List.of();
            }
            List<Map<String, Object>> values = (List<Map<String, Object>>) timeDim.get("values");
            List<String> timeValues = new ArrayList<>();
            for (Map<String, Object> v : values) {
                timeValues.add(String.valueOf(v.get("id")));
            }
            List<Map<String, Object>> datasets = (List<Map<String, Object>>) data.get("dataSets");
            Map<String, Object> seriesMap = (Map<String, Object>) datasets.get(0).get("series");
            List<Map<String, Object>> results = new ArrayList<>();
            for (Object sdObj : seriesMap.values()) {
                Map<String, Object> sd = (Map<String, Object>) sdObj;
                Map<String, Object> observations = (Map<String, Object>) sd.get("observations");
                if (observations == null) {
                    continue;
                }
                for (Map.Entry<String, Object> obs : observations.entrySet()) {
                    int idx = Integer.parseInt(obs.getKey());
                    if (idx >= timeValues.size()) {
                        continue;
                    }
                    Object valListObj = obs.getValue();
                    if (!(valListObj instanceof List<?> valList) || valList.isEmpty() || valList.get(0) == null) {
                        continue;
                    }
                    results.add(Map.of("date", timeValues.get(idx), "value", valList.get(0)));
                }
            }
            results.sort(Comparator.comparing(r -> stringOrBlank(r.get("date"))));
            return results;
        } catch (Exception ex) {
            throw new IllegalStateException("Chyba parsování dat ECB: " + ex.getMessage(), ex);
        }
    }

    public static List<Map<String, Object>> computeYoyFromIndex(List<Map<String, Object>> series) {
        Map<String, Double> byDate = new LinkedHashMap<>();
        for (Map<String, Object> point : series) {
            String date = stringOrBlank(point.get("date"));
            Object rawVal = point.get("value");
            if (date.isBlank() || rawVal == null) {
                continue;
            }
            try {
                byDate.put(date, Double.parseDouble(String.valueOf(rawVal)));
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String date : byDate.keySet().stream().sorted().toList()) {
            String prevDate = prevYearPeriod(date);
            if (prevDate == null) {
                continue;
            }
            Double prev = byDate.get(prevDate);
            Double current = byDate.get(date);
            if (prev == null || current == null || prev == 0.0) {
                continue;
            }
            double yoy = (current / prev - 1.0) * 100.0;
            out.add(Map.of("date", date, "value", Math.round(yoy * 10000.0) / 10000.0));
        }
        return out;
    }

    public static String ecbErrorDetailCz(String flow, String key, int status, String body) {
        String snippet = body != null ? body.trim() : "";
        if (snippet.length() > 280) {
            snippet = snippet.substring(0, 280) + "…";
        }
        if (status == 404) {
            return "ECB u této země neposkytuje řadu " + flow + "/" + key + ". Zkuste jiný ukazatel z nabídky pro danou zemi.";
        }
        if (status == 400) {
            return "ECB odmítlo dotaz (" + flow + "/" + key + "). Zkuste jiný ukazatel nebo zemi.";
        }
        if (status == 429 || status == 503) {
            return "Data ECB se načítají — zkuste to za několik sekund znovu.";
        }
        if (!snippet.isBlank() && snippet.toLowerCase().contains("blocked")) {
            return "Dočasný limit ECB — data se načtou z mezipaměti při opakování.";
        }
        if (!snippet.isBlank()) {
            return "ECB API chyba (" + status + "): " + snippet;
        }
        return "ECB API vrátilo chybu HTTP " + status + ".";
    }

    public static String buildUrl(String flow, String key, Map<String, String> queryParams) {
        StringBuilder url = new StringBuilder(EcbCuratedCatalog.ECB_BASE_URL)
                .append("/")
                .append(flow.trim())
                .append("/")
                .append(key.trim());
        if (queryParams != null && !queryParams.isEmpty()) {
            url.append("?");
            List<String> pairs = new ArrayList<>();
            for (Map.Entry<String, String> entry : queryParams.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isBlank()) {
                    pairs.add(entry.getKey() + "=" + java.net.URLEncoder.encode(entry.getValue(), java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            url.append(String.join("&", pairs));
        }
        return url.toString();
    }

    private static String prevYearPeriod(String period) {
        String d = stringOrBlank(period);
        Matcher q = YEAR_Q.matcher(d);
        if (q.matches()) {
            return (Integer.parseInt(q.group(1)) - 1) + "-Q" + q.group(2);
        }
        if (YEAR_ONLY.matcher(d).matches()) {
            return String.valueOf(Integer.parseInt(d) - 1);
        }
        Matcher ym = YEAR_MONTH.matcher(d);
        if (ym.matches()) {
            return (Integer.parseInt(ym.group(1)) - 1) + "-" + ym.group(2);
        }
        return null;
    }

    private static String stringOrBlank(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    public static class EcbUpstreamException extends RuntimeException {
        private final int status;

        public EcbUpstreamException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
