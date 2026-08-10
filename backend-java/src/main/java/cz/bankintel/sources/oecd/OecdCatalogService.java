package cz.bankintel.sources.oecd;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.domain.dto.SourceDtos.SourceCreateRequest;
import cz.bankintel.service.sources.SourceService;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OecdCatalogService {

    private static final String OECD_SDMX_HUB = "https://sdmx.oecd.org/public/rest";
    private static final String OECD_STATS = "https://stats.oecd.org";
    private static final String OECD_BASE_URL_LEGACY = "https://stats.oecd.org";
    private static final String OECD_SD_V2_BASE = "https://sdmx.oecd.org/public/rest";

    private static final long DATAFLOW_TTL_MS = Duration.ofHours(24).toMillis();
    private static final long SERIES_TTL_MS = Duration.ofHours(1).toMillis();

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final SourceService sourceService;

    private final ReentrantLock cacheLock = new ReentrantLock();
    private Map<String, Object> dataflowCache;
    private long dataflowFetchedAtMs;
    private final Map<String, CachedPayload> seriesCache = new LinkedHashMap<>();

    public OecdCatalogService(ObjectMapper objectMapper, SourceService sourceService) {
        this.objectMapper = objectMapper;
        this.sourceService = sourceService;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public Map<String, Object> getCatalog() {
        long now = System.currentTimeMillis();
        cacheLock.lock();
        try {
            if (dataflowCache != null && (now - dataflowFetchedAtMs) < DATAFLOW_TTL_MS) {
                Map<String, Object> out = new LinkedHashMap<>(dataflowCache);
                out.put("fetched_at", dataflowFetchedAtMs / 1000.0);
                out.put("cached", true);
                out.put("ttl_seconds", DATAFLOW_TTL_MS / 1000);
                out.put("oecd_data_api_notice_cs", oecdNotice());
                return out;
            }
        } finally {
            cacheLock.unlock();
        }

        Map<String, Object> fresh = fetchDataflows();
        cacheLock.lock();
        try {
            dataflowCache = new LinkedHashMap<>(fresh);
            dataflowFetchedAtMs = now;
        } finally {
            cacheLock.unlock();
        }
        Map<String, Object> out = new LinkedHashMap<>(fresh);
        out.put("fetched_at", dataflowFetchedAtMs / 1000.0);
        out.put("cached", false);
        out.put("ttl_seconds", DATAFLOW_TTL_MS / 1000);
        out.put("oecd_data_api_notice_cs", oecdNotice());
        return out;
    }

    public Map<String, Object> refreshCatalog() {
        cacheLock.lock();
        try {
            dataflowCache = null;
            dataflowFetchedAtMs = 0;
            seriesCache.clear();
        } finally {
            cacheLock.unlock();
        }
        return getCatalog();
    }

    public Map<String, Object> getSeriesForDataset(String dataset, boolean refresh) {
        String ds = dataset != null ? dataset.trim() : "";
        if (ds.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chybi kod datasetu.");
        }
        long now = System.currentTimeMillis();
        cacheLock.lock();
        try {
            CachedPayload cached = seriesCache.get(ds.toUpperCase(Locale.ROOT));
            if (!refresh && cached != null && (now - cached.fetchedAtMs()) < SERIES_TTL_MS) {
                Map<String, Object> out = new LinkedHashMap<>(cached.payload());
                out.put("fetched_at", cached.fetchedAtMs() / 1000.0);
                out.put("cached", true);
                out.put("ttl_seconds", SERIES_TTL_MS / 1000);
                return out;
            }
        } finally {
            cacheLock.unlock();
        }

        Map<String, Object> payload = fetchSeriesForDataset(ds);
        cacheLock.lock();
        try {
            seriesCache.put(ds.toUpperCase(Locale.ROOT), new CachedPayload(now, new LinkedHashMap<>(payload)));
        } finally {
            cacheLock.unlock();
        }
        Map<String, Object> out = new LinkedHashMap<>(payload);
        out.put("fetched_at", now / 1000.0);
        out.put("cached", false);
        out.put("ttl_seconds", SERIES_TTL_MS / 1000);
        return out;
    }

    public Map<String, Object> getDataflowStructure(String agency, String dataflow, String version, boolean refresh) {
        String agencyNorm = safeSegment(agency);
        String dataflowNorm = safeSegment(dataflow);
        String versionNorm = (version == null || version.isBlank()) ? "+" : version.trim();
        if (agencyNorm.isBlank() || dataflowNorm.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chybi agency nebo dataflow.");
        }
        String path = "/datastructure/" + agencyNorm + "/" + dataflowNorm + "/" + urlEncode(versionNorm);
        String query = toQuery(Map.of("references", "all", "detail", "referencepartial"));
        URI uri = URI.create(OECD_SD_V2_BASE + path + "?" + query);
        try {
            return getJson(uri);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OECD structure load failed");
        }
    }

    public Map<String, Object> addSource(Map<String, Object> payload) {
        String code = stringOrBlank(payload.get("set_id"));
        if (code.isBlank() || !code.contains("/")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Neplatny kod OECD.");
        }
        if (code.toUpperCase(Locale.ROOT).contains("||DATAFLOW")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Dataflow nelze pridat primo - nejdriv nactete rady pro tento dataset.");
        }

        String[] split = code.split("/", 2);
        String datasetCode = split[0].trim();
        String filter = split[1].trim();
        if (datasetCode.isBlank() || filter.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Neplatny kod OECD.");
        }

        String displayName = stringOrBlank(payload.get("name"));
        if (displayName.isBlank()) {
            displayName = "OECD · " + code;
        }
        Integer refreshInterval = toInteger(payload.get("refresh_interval_minutes"));
        if (refreshInterval == null) {
            refreshInterval = 1440;
        }
        Boolean active = toBoolean(payload.get("active"));
        if (active == null) {
            active = true;
        }

        Map<String, Object> queryParams = new LinkedHashMap<>();
        queryParams.put("format", "csvfilewithlabels");
        queryParams.put("startTime", "2010");
        queryParams.put("dimensionAtObservation", "AllDimensions");
        queryParams.put("lastNObservations", 800);
        Object extra = payload.get("query_params");
        if (extra instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    queryParams.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        }

        SourceCreateRequest request = new SourceCreateRequest(
                displayName,
                "oecd",
                OECD_BASE_URL_LEGACY,
                "/SDMX-JSON/data/" + datasetCode + "/" + filter + "/all",
                "GET",
                "none",
                null,
                Map.of("User-Agent", "banking-bi/1.0"),
                queryParams,
                refreshInterval,
                active,
                displayName,
                null);
        Map<String, Object> created = sourceService.createSource(request);
        return Map.of(
                "id", created.get("id"),
                "name", displayName,
                "set_id", code,
                "full_path", code);
    }

    private Map<String, Object> fetchDataflows() {
        URI uri = URI.create(OECD_SDMX_HUB + "/dataflow/OECD/all/latest");
        Map<String, Object> payload;
        try {
            payload = getJson(uri);
        } catch (Exception ex) {
            return Map.of(
                    "categories", List.of(),
                    "total_sets", 0,
                    "source", "error",
                    "errors", List.of("OECD API je docasne nedostupne."));
        }

        List<Map<String, String>> rows = parseDataflowRows(payload);
        rows.sort(Comparator.comparing(v -> stringOrBlank(v.get("name")).toLowerCase(Locale.ROOT)));

        Map<String, List<Map<String, String>>> buckets = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            String name = stringOrBlank(row.get("name"));
            String letter = name.isBlank() ? "#" : name.substring(0, 1).toUpperCase(Locale.ROOT);
            if (!letter.matches("[A-Z0-9]")) {
                letter = "#";
            }
            buckets.computeIfAbsent(letter, ignored -> new ArrayList<>()).add(row);
        }

        List<String> letters = new ArrayList<>(buckets.keySet());
        letters.sort(String::compareTo);
        List<Map<String, Object>> categories = new ArrayList<>();
        for (String letter : letters) {
            List<Map<String, Object>> sets = new ArrayList<>();
            for (Map<String, String> item : buckets.getOrDefault(letter, List.of())) {
                String id = item.get("id");
                String name = item.get("name");
                Map<String, Object> set = new LinkedHashMap<>();
                set.put("id", id + "||DATAFLOW");
                set.put("set_id", id + "||DATAFLOW");
                set.put("name", name);
                set.put("full_path", "OECD > " + letter + " > " + name);
                set.put("kind", "dataflow");
                set.put("oecd_dataset", id);
                set.put("oecd_agency", item.get("agency_id"));
                set.put("oecd_version", item.get("version"));
                set.put("period", "");
                set.put("territory", "OECD");
                set.put("last_update", "");
                sets.add(set);
            }
            categories.add(Map.of(
                    "name", letter,
                    "path", "OECD > " + letter,
                    "children", List.of(),
                    "sets", sets));
        }

        return Map.of(
                "categories",
                List.of(Map.of(
                        "name", "OECD — vsechny datove sady (abecedne)",
                        "path", "OECD",
                        "children", categories,
                        "sets", List.of())),
                "total_sets", rows.size(),
                "source", "live",
                "oecd_fetch_mode", "bulk");
    }

    private List<Map<String, String>> parseDataflowRows(Map<String, Object> payload) {
        List<Map<String, String>> out = new ArrayList<>();
        Object refsObj = payload.get("references");
        if (!(refsObj instanceof Map<?, ?> refs)) {
            return out;
        }
        for (Map.Entry<?, ?> entry : refs.entrySet()) {
            String urn = entry.getKey() != null ? String.valueOf(entry.getKey()) : "";
            if (!urn.contains("Dataflow")) {
                continue;
            }
            if (!(entry.getValue() instanceof Map<?, ?> item)) {
                continue;
            }
            String agencyId = stringOrBlank(item.get("agencyID"));
            if (!agencyId.toUpperCase(Locale.ROOT).contains("OECD")) {
                continue;
            }
            String id = stringOrBlank(item.get("id"));
            if (id.isBlank()) {
                continue;
            }
            String version = stringOrBlank(item.get("version"));
            if (version.isBlank()) {
                version = "1.0";
            }
            String name = stringOrBlank(item.get("name"));
            if (name.isBlank()) {
                name = stringOrBlank(item.get("description"));
            }
            if (name.isBlank()) {
                name = id;
            }
            out.add(Map.of("id", id, "agency_id", agencyId, "version", version, "name", name));
        }
        return out;
    }

    private Map<String, Object> fetchSeriesForDataset(String datasetId) {
        String path = "/sdmx-json/data/" + urlEncode(datasetId) + "/all";
        String query = toQuery(Map.of("dimensionAtObservation", "AllDimensions", "detail", "serieskeysonly"));
        URI uri = URI.create(OECD_STATS + path + "?" + query);
        try {
            Map<String, Object> payload = getJson(uri);
            Map<String, Object> data = asMap(payload.get("data"));
            List<Object> structures = asList(data.get("structures"));
            if (structures.isEmpty()) {
                throw new IOException("missing structures");
            }
            Map<String, Object> structure = asMap(structures.get(0));
            Map<String, Object> dimensions = asMap(structure.get("dimensions"));
            List<Object> seriesDims = asList(dimensions.get("series"));

            Integer refDim = null;
            for (int i = 0; i < seriesDims.size(); i++) {
                Map<String, Object> dim = asMap(seriesDims.get(i));
                String did = stringOrBlank(dim.get("id")).toUpperCase(Locale.ROOT);
                if (did.equals("REF_AREA") || did.equals("LOCATION") || did.equals("AREA") || did.equals("COU")) {
                    refDim = i;
                    break;
                }
            }

            List<Object> dataSets = asList(data.get("dataSets"));
            Map<String, Object> firstDataSet = dataSets.isEmpty() ? Map.of() : asMap(dataSets.get(0));
            Map<String, Object> seriesBlock = asMap(firstDataSet.get("series"));

            Map<String, List<Map<String, Object>>> byArea = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : seriesBlock.entrySet()) {
                String key = entry.getKey();
                String filter = decodeSeriesKey(seriesDims, key);
                List<String> parts = List.of(filter.split("\\."));
                String ref = "";
                if (refDim != null && refDim < parts.size()) {
                    ref = parts.get(refDim);
                }
                String bucket = ref.isBlank() ? "_OTHER_" : ref;
                Map<String, Object> leaf = new LinkedHashMap<>();
                leaf.put("id", datasetId + "/" + filter);
                leaf.put("set_id", datasetId + "/" + filter);
                leaf.put("name", filter.length() > 200 ? filter.substring(0, 200) + "…" : filter);
                leaf.put("full_path", "OECD > " + datasetId + " > " + filter);
                leaf.put("kind", "selection");
                leaf.put("dataset_code", datasetId);
                leaf.put("dataset_name", datasetId);
                leaf.put("oecd_dataset", datasetId);
                leaf.put("oecd_filter", filter);
                leaf.put("period", "dle rady");
                leaf.put("territory", ref.isBlank() ? "OECD" : ref);
                leaf.put("ref_area", ref);
                leaf.put("last_update", "");
                byArea.computeIfAbsent(bucket, ignored -> new ArrayList<>()).add(leaf);
            }

            List<String> areaKeys = new ArrayList<>(byArea.keySet());
            areaKeys.sort(String::compareToIgnoreCase);
            List<Map<String, Object>> children = new ArrayList<>();
            int total = 0;
            for (String area : areaKeys) {
                String label = "_OTHER_".equals(area) ? "(ostatni)" : "Zeme / oblast: " + area;
                List<Map<String, Object>> sets = byArea.getOrDefault(area, List.of());
                sets.sort(Comparator.comparing(v -> stringOrBlank(v.get("oecd_filter")).toLowerCase(Locale.ROOT)));
                total += sets.size();
                children.add(Map.of(
                        "name", label,
                        "path", "OECD::" + datasetId + " > " + label,
                        "children", List.of(),
                        "sets", sets));
            }

            return Map.of(
                    "categories", List.of(Map.of(
                            "name", "Dataset " + datasetId + " — vsechny rady",
                            "path", "OECD::" + datasetId,
                            "children", children,
                            "sets", List.of())),
                    "total_sets", total,
                    "dataset_id", datasetId,
                    "source", "live");
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OECD upstream error");
        }
    }

    private static String decodeSeriesKey(List<Object> seriesDims, String seriesKey) {
        String[] keyParts = seriesKey.split(":");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < keyParts.length; i++) {
            int pos;
            try {
                pos = Integer.parseInt(keyParts[i]);
            } catch (NumberFormatException ex) {
                out.add("?");
                continue;
            }
            if (i >= seriesDims.size()) {
                break;
            }
            Map<String, Object> dim = asMap(seriesDims.get(i));
            List<Object> values = asList(dim.get("values"));
            if (pos >= 0 && pos < values.size()) {
                Map<String, Object> value = asMap(values.get(pos));
                out.add(stringOrBlank(value.get("id")));
            } else {
                out.add("?");
            }
        }
        return String.join(".", out);
    }

    private Map<String, Object> getJson(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(180))
                .header("Accept", "application/json")
                .header("User-Agent", "banking-bi/1.0")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return objectMapper.readValue(response.body(), new TypeReference<>() {});
    }

    private static String oecdNotice() {
        return "OECD Data API poskytuje programovy pristup pres SDMX standard. Dotazy se skladaji z agency, "
                + "dataflow, verze a hodnot dimenzi.";
    }

    private static String safeSegment(String value) {
        String v = stringOrBlank(value);
        if (v.isBlank()) {
            return "";
        }
        return v.replaceAll("[^A-Za-z0-9_.@+-]", "");
    }

    private static String toQuery(Map<String, Object> params) {
        List<String> pairs = new ArrayList<>();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            pairs.add(urlEncode(entry.getKey()) + "=" + urlEncode(String.valueOf(entry.getValue())));
        }
        return String.join("&", pairs);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String stringOrBlank(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    private static Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Boolean toBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String raw = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(raw) || "1".equals(raw) || "yes".equals(raw) || "on".equals(raw)) {
            return true;
        }
        if ("false".equals(raw) || "0".equals(raw) || "no".equals(raw) || "off".equals(raw)) {
            return false;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private static List<Object> asList(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return List.of();
    }

    private record CachedPayload(long fetchedAtMs, Map<String, Object> payload) {}
}
