package cz.bankintel.sources.imf;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.domain.dto.SourceDtos.SourceCreateRequest;
import cz.bankintel.service.sources.SourceService;
import java.io.IOException;
import java.io.StringReader;
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
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

@Service
public class ImfCatalogService {

    private static final String IMF_BASE_URL = "https://api.imf.org/external/sdmx/3.0";
    private static final long CATALOG_TTL_MS = Duration.ofHours(12).toMillis();

    private static final List<Map<String, Object>> FREQUENCIES = List.of(
            Map.of("code", "M", "label", "Mesicne"),
            Map.of("code", "Q", "label", "Ctvrtletne"),
            Map.of("code", "A", "label", "Rocne"));

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final SourceService sourceService;
    private final ReentrantLock cacheLock = new ReentrantLock();

    private Map<String, Object> fullPayloadCache;
    private long fetchedAtMs;

    public ImfCatalogService(ObjectMapper objectMapper, SourceService sourceService) {
        this.objectMapper = objectMapper;
        this.sourceService = sourceService;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public Map<String, Object> getBootstrapCatalog() {
        Map<String, Object> out = new LinkedHashMap<>(bootstrapPayload());
        out.put("fetched_at", System.currentTimeMillis() / 1000.0);
        out.put("cached", false);
        out.put("ttl_seconds", CATALOG_TTL_MS / 1000);
        return out;
    }

    public Map<String, Object> getFullCatalog(boolean forceRefresh) {
        long now = System.currentTimeMillis();
        cacheLock.lock();
        try {
            if (!forceRefresh && fullPayloadCache != null && (now - fetchedAtMs) < CATALOG_TTL_MS) {
                Map<String, Object> out = new LinkedHashMap<>(fullPayloadCache);
                out.put("fetched_at", fetchedAtMs / 1000.0);
                out.put("cached", true);
                out.put("ttl_seconds", CATALOG_TTL_MS / 1000);
                return out;
            }
        } finally {
            cacheLock.unlock();
        }

        Map<String, Object> fresh = fetchCatalogMatrix();
        cacheLock.lock();
        try {
            fullPayloadCache = new LinkedHashMap<>(fresh);
            fetchedAtMs = now;
        } finally {
            cacheLock.unlock();
        }
        Map<String, Object> out = new LinkedHashMap<>(fresh);
        out.put("fetched_at", fetchedAtMs / 1000.0);
        out.put("cached", false);
        out.put("ttl_seconds", CATALOG_TTL_MS / 1000);
        return out;
    }

    public Map<String, Object> getDatasetStructure(String datasetId) {
        String ds = datasetId != null ? datasetId.trim().toUpperCase(Locale.ROOT) : "";
        if (!ds.matches("^[A-Z][A-Z0-9_]{0,14}$")) {
            return Map.of(
                    "countries", List.of(),
                    "indicators", List.of(),
                    "errors", List.of("Neplatny kod databaze IMF."),
                    "dataset_id", ds,
                    "http_status", null,
                    "last_url", "");
        }

        String url = IMF_BASE_URL + "/DataStructure/" + ds;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(22))
                    .header("Accept", "application/xml")
                    .header("User-Agent", "banking-bi/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body() == null || response.body().trim().length() < 50) {
                return Map.of(
                        "countries", List.of(),
                        "indicators", List.of(),
                        "errors", List.of("IMF DataStructure/" + ds + ": HTTP " + response.statusCode()),
                        "dataset_id", ds,
                        "http_status", response.statusCode(),
                        "last_url", url,
                        "source", "empty");
            }
            ParsedLists parsed = parseDataStructure(response.body());
            return Map.of(
                    "countries", parsed.countries(),
                    "indicators", parsed.indicators(),
                    "errors", parsed.errors(),
                    "dataset_id", ds,
                    "http_status", response.statusCode(),
                    "last_url", url,
                    "source", parsed.countries().isEmpty() && parsed.indicators().isEmpty() ? "empty" : "live");
        } catch (Exception ex) {
            return Map.of(
                    "countries", List.of(),
                    "indicators", List.of(),
                    "errors", List.of(friendlyError(ex)),
                    "dataset_id", ds,
                    "http_status", null,
                    "last_url", url,
                    "source", "error");
        }
    }

    public Map<String, Object> validateSeries(Map<String, Object> payload) {
        String setId = stringOrBlank(payload.get("set_id"));
        if (!setId.contains("/")) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "IMF nahled vyzaduje konkretni CompactData dotaz databaze/rady.");
        }
        return probeCompactData(setId);
    }

    public Map<String, Object> addSource(Map<String, Object> payload) {
        String setId = stringOrBlank(payload.get("set_id"));
        if (!setId.contains("/")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Neplatny IMF set_id.");
        }
        String[] split = setId.split("/", 2);
        String database = split[0].trim().toUpperCase(Locale.ROOT);
        String queryPart = split[1].trim();
        if (database.isBlank() || queryPart.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Neplatny IMF set_id.");
        }

        Map<String, Object> validation = probeCompactData(database + "/" + queryPart);
        if (!Boolean.TRUE.equals(validation.get("ok"))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    stringOrBlank(validation.get("message_cs")).isBlank()
                            ? "Tato kombinace databaze a indikatoru neni platna."
                            : stringOrBlank(validation.get("message_cs")));
        }

        String displayName = stringOrBlank(payload.get("name"));
        if (displayName.isBlank()) {
            displayName = "IMF · " + setId;
        }

        Map<String, Object> queryParams = new LinkedHashMap<>();
        queryParams.put("startPeriod", "1990");
        queryParams.put("endPeriod", "2030");
        Object rawQp = payload.get("query_params");
        if (rawQp instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    queryParams.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        }
        if (Boolean.TRUE.equals(payload.get("imf_ifs_flag")) || Boolean.TRUE.equals(payload.get("imfIFSFlag"))) {
            queryParams.put("c[IFS_Flag]", "True");
        }

        Integer refreshInterval = toInteger(payload.get("refresh_interval_minutes"));
        if (refreshInterval == null) {
            refreshInterval = 1440;
        }
        Boolean active = toBoolean(payload.get("active"));
        if (active == null) {
            active = true;
        }

        SourceCreateRequest request = new SourceCreateRequest(
                displayName,
                "imf",
                IMF_BASE_URL,
                "/CompactData/" + database + "/" + queryPart,
                "GET",
                "none",
                null,
                Map.of("Accept", "application/json", "User-Agent", "banking-bi/1.0"),
                queryParams,
                refreshInterval,
                active,
                displayName,
                null);
        Map<String, Object> created = sourceService.createSource(request);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", created.get("id"));
        out.put("name", displayName);
        out.put("set_id", setId);
        out.put("full_path", setId);
        out.put("validation", validation);
        return out;
    }

    private Map<String, Object> fetchCatalogMatrix() {
        Map<String, Object> ds = getDatasetStructure("CPI");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> countries = (List<Map<String, Object>>) ds.getOrDefault("countries", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> indicators = (List<Map<String, Object>>) ds.getOrDefault("indicators", List.of());
        @SuppressWarnings("unchecked")
        List<String> errors = new ArrayList<>((List<String>) ds.getOrDefault("errors", List.of()));

        if (countries.isEmpty()) {
            countries = List.of();
            errors.add("IMF nevratil seznam zemi z DataStructure/CPI.");
        }
        if (indicators.isEmpty()) {
            indicators = List.of();
            errors.add("IMF nevratil seznam indikatoru z DataStructure/CPI.");
        }

        Map<String, Object> compactProbe = probeCompactData("CPI/M.US.PCPI_IX");
        String connectorStatus = Boolean.TRUE.equals(compactProbe.get("ok")) ? "partial" : "fail";
        String source = countries.isEmpty() || indicators.isEmpty() ? "hybrid" : "live";

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", "matrix");
        out.put("categories", List.of());
        out.put("total_sets", countries.size() * indicators.size());
        out.put("countries", countries);
        out.put("indicators", indicators);
        out.put("frequencies", FREQUENCIES);
        out.put("errors", errors);
        out.put("source", source);
        out.put("imf_primary_datastructure_id", "CPI");
        out.put("imf_catalog_health", source.equals("live") ? "live_metastructure" : "hybrid_fallback");
        out.put("imf_connector_status", connectorStatus);
        out.put("imf_sdmx3", true);
        out.put("imf_upstream", Map.of(
                "sdmx_base", IMF_BASE_URL,
                "imf_api_kind", "sdmx_3_apim",
                "note", "Hlavni UI: /api/imf/browse-tree a /api/imf/country/{kod}."));
        out.put("imf_compactdata_probe", compactProbe);
        out.put("imf_compactdata_databases", imfDatabaseChoices());
        out.put("imf_theme_structure_ifs_discontinued", true);
        out.put("imf_ifs_flag_constraint_help",
                "Byvala IFS data: v dotazu lze pouzit constrain c[IFS_Flag]=True tam, kde to API podporuje.");
        return out;
    }

    private static List<Map<String, String>> imfDatabaseChoices() {
        return List.of(
                Map.of("id", "CPI", "label", "Consumer Price Index (CPI)"),
                Map.of("id", "ER", "label", "Exchange Rate (ER)"),
                Map.of("id", "EER", "label", "Effective Exchange Rate (EER)"),
                Map.of("id", "NEA", "label", "National Economic Accounts (NEA)"),
                Map.of("id", "BOP", "label", "Balance of Payments (BOP)"),
                Map.of("id", "IIP", "label", "International Investment Position (IIP)"),
                Map.of("id", "IL", "label", "International Liquidity (IL)"),
                Map.of("id", "ITG", "label", "International Trade in Goods (ITG)"),
                Map.of("id", "MFS", "label", "Monetary and Financial Statistics (MFS)"),
                Map.of("id", "QGFS", "label", "Quarterly Government Finance Statistics (QGFS)"),
                Map.of("id", "LS", "label", "Labor Force Statistics (LS)"),
                Map.of("id", "FA", "label", "Fund Accounts (FA)"),
                Map.of("id", "SPE", "label", "Special Purpose Entities (SPE)"),
                Map.of("id", "PPI", "label", "Producer Price Indexes (PPI)"),
                Map.of("id", "DOT", "label", "Direction of Trade (DOT)"),
                Map.of("id", "IFS", "label", "IFS (legacy CompactData kod)"));
    }

    private Map<String, Object> probeCompactData(String code) {
        String path = stringOrBlank(code).replaceFirst("^/+", "");
        String url = IMF_BASE_URL + "/CompactData/" + path;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(16))
                    .header("Accept", "application/json")
                    .header("User-Agent", "banking-bi/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            boolean bodyOk = response.body() != null && response.body().trim().length() > 20;
            boolean ok = response.statusCode() == 200 && bodyOk;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("url", url);
            out.put("ok", ok);
            out.put("http_status", response.statusCode());
            out.put("reason_code", ok ? "ok" : "invalid_dataset_or_key");
            if (!ok) {
                out.put("message_cs", response.statusCode() == 200 ? "Prazdna odpoved IMF." : "IMF API vratilo HTTP " + response.statusCode() + ".");
            }
            return out;
        } catch (Exception ex) {
            return Map.of(
                    "url", url,
                    "ok", false,
                    "http_status", null,
                    "reason_code", "upstream_error",
                    "message_cs", friendlyError(ex));
        }
    }

    private static ParsedLists parseDataStructure(String xmlText) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        var builder = factory.newDocumentBuilder();
        var document = builder.parse(new InputSource(new StringReader(xmlText)));
        document.getDocumentElement().normalize();

        List<Map<String, Object>> countries = new ArrayList<>();
        List<Map<String, Object>> indicators = new ArrayList<>();

        NodeList codeLists = document.getElementsByTagNameNS("*", "CodeList");
        List<CodeListBucket> buckets = new ArrayList<>();
        for (int i = 0; i < codeLists.getLength(); i++) {
            Node node = codeLists.item(i);
            if (!(node instanceof Element cl)) {
                continue;
            }
            String codeListId = stringOrBlank(cl.getAttribute("id"));
            List<CodeEntry> entries = new ArrayList<>();
            NodeList codes = cl.getElementsByTagNameNS("*", "Code");
            for (int j = 0; j < codes.getLength(); j++) {
                Node codeNode = codes.item(j);
                if (!(codeNode instanceof Element codeEl)) {
                    continue;
                }
                String code = stringOrBlank(codeEl.getAttribute("id"));
                if (code.isBlank()) {
                    continue;
                }
                String name = code;
                NodeList names = codeEl.getElementsByTagNameNS("*", "Name");
                if (names.getLength() > 0) {
                    String candidate = stringOrBlank(names.item(0).getTextContent());
                    if (!candidate.isBlank()) {
                        name = candidate;
                    }
                }
                entries.add(new CodeEntry(code, name));
            }
            if (!entries.isEmpty()) {
                buckets.add(new CodeListBucket(codeListId, entries));
            }
        }

        CodeListBucket area = pickAreaBucket(buckets);
        if (area != null) {
            for (CodeEntry entry : area.entries()) {
                countries.add(Map.of("code", entry.code(), "name", entry.name()));
            }
            countries.sort(Comparator.comparing(v -> stringOrBlank(v.get("name")).toLowerCase(Locale.ROOT)));
        }

        CodeListBucket indicatorBucket = pickIndicatorBucket(buckets, area != null ? area.id() : "");
        if (indicatorBucket != null) {
            for (CodeEntry entry : indicatorBucket.entries()) {
                indicators.add(Map.of("code", entry.code(), "label", entry.name(), "default_freq", "M"));
            }
            indicators.sort(Comparator.comparing(v -> stringOrBlank(v.get("label")).toLowerCase(Locale.ROOT)));
        }

        return new ParsedLists(countries, indicators, List.of());
    }

    private static CodeListBucket pickAreaBucket(List<CodeListBucket> buckets) {
        for (CodeListBucket bucket : buckets) {
            String u = bucket.id().toUpperCase(Locale.ROOT);
            if (u.contains("AREA") || u.contains("REF") || u.contains("COUNTRY") || u.contains("GEO")) {
                return bucket;
            }
        }
        CodeListBucket best = null;
        for (CodeListBucket bucket : buckets) {
            if (bucket.entries().size() > 50 && (best == null || bucket.entries().size() > best.entries().size())) {
                best = bucket;
            }
        }
        return best;
    }

    private static CodeListBucket pickIndicatorBucket(List<CodeListBucket> buckets, String areaId) {
        for (CodeListBucket bucket : buckets) {
            if (bucket.id().equalsIgnoreCase(areaId)) {
                continue;
            }
            String u = bucket.id().toUpperCase(Locale.ROOT);
            if (u.contains("INDIC") || u.contains("SERIES")) {
                return bucket;
            }
        }
        CodeListBucket best = null;
        for (CodeListBucket bucket : buckets) {
            if (bucket.id().equalsIgnoreCase(areaId)) {
                continue;
            }
            if (best == null || bucket.entries().size() > best.entries().size()) {
                best = bucket;
            }
        }
        return best;
    }

    private static Map<String, Object> bootstrapPayload() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", "search_first_bootstrap");
        out.put("categories", List.of());
        out.put("total_sets", 0);
        out.put(
                "intro_cs",
                "IMF katalog je velmi rozsahly. Vyberte tematickou databazi (CPI, ER, NEA, BOP, …), "
                        + "pote nactete seznam oblasti a indikatoru jen pro vybranou databazi.");
        out.put("countries", List.of());
        out.put("indicators", List.of());
        out.put("frequencies", FREQUENCIES);
        out.put("errors", List.of());
        out.put("source", "bootstrap");
        out.put("imf_primary_datastructure_id", null);
        out.put("imf_catalog_health", "search_first_only");
        out.put("imf_connector_status", "unknown_until_probe_or_dataset_load");
        out.put("imf_sdmx3", true);
        out.put("imf_browse_endpoint", "/api/imf/browse-tree");
        out.put("imf_compactdata_databases", imfDatabaseChoices());
        out.put("imf_theme_structure_ifs_discontinued", true);
        out.put("imf_ifs_flag_constraint_help",
                "Byvala IFS data: v dotazu lze pouzit constrain c[IFS_Flag]=True tam, kde to API podporuje.");
        out.put("imf_upstream", Map.of(
                "sdmx_base", IMF_BASE_URL,
                "imf_api_kind", "sdmx_3_apim",
                "note", "Hlavni UI: /api/imf/browse-tree a /api/imf/country/{kod}."));
        return out;
    }

    private static String friendlyError(Exception ex) {
        String msg = stringOrBlank(ex.getMessage()).toLowerCase(Locale.ROOT);
        if (msg.contains("timeout")) {
            return "Spojeni s IMF vyprselo. Zkuste to prosim pozdeji.";
        }
        if (msg.contains("connect") || msg.contains("connection")) {
            return "IMF server docasne neodpovedel. Zkuste to pozdeji.";
        }
        return "IMF Data API chyba: " + stringOrBlank(ex.getMessage());
    }

    private static String toQuery(Map<String, Object> params) {
        List<String> pairs = new ArrayList<>();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            pairs.add(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(String.valueOf(entry.getValue()), StandardCharsets.UTF_8));
        }
        return String.join("&", pairs);
    }

    private Map<String, Object> getJson(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(45))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return objectMapper.readValue(response.body(), new TypeReference<>() {});
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

    private record CodeEntry(String code, String name) {}

    private record CodeListBucket(String id, List<CodeEntry> entries) {}

    private record ParsedLists(List<Map<String, Object>> countries, List<Map<String, Object>> indicators, List<String> errors) {}
}
