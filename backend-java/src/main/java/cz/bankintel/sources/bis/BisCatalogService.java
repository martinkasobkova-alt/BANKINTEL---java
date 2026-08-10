package cz.bankintel.sources.bis;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import javax.xml.parsers.DocumentBuilderFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

@Slf4j
@Service
public class BisCatalogService {

    public static final String BIS_API_ROOT = "https://stats.bis.org/api/v1";
    public static final String BIS_GENERICDATA_ACCEPT = "application/vnd.sdmx.genericdata+xml;version=2.1";
    public static final String BIS_STRUCTURE_ACCEPT = "application/vnd.sdmx.structure+xml;version=2.1";

    private static final long DATAFLOW_TTL_MS = Duration.ofHours(24).toMillis();
    private static final long SERIES_TTL_MS = Duration.ofHours(1).toMillis();
    private static final long STRUCTURE_TTL_MS = Duration.ofDays(7).toMillis();
    private static final int SERIES_LAZY_COUNTRY_THRESHOLD = 8000;
    private static final int MAX_CODES_PER_DIMENSION = 400;

    private static final Set<String> ALLOWED_PREVIEW_QUERY = Set.of(
            "detail", "startPeriod", "endPeriod", "firstNObservations", "lastNObservations");

    private final HttpClient httpClient;
    private final ReentrantLock lock = new ReentrantLock();

    private Map<String, Object> dataflowTreeCache;
    private List<Map<String, String>> dataflowListCache = List.of();
    private long dataflowFetchedAtMs;

    private final Map<String, CacheEntry> seriesCache = new HashMap<>();
    private final Map<String, StructureCacheEntry> structureCache = new HashMap<>();

    public BisCatalogService() {
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).build();
    }

    public long dataflowTtlSeconds() {
        return DATAFLOW_TTL_MS / 1000;
    }

    public long seriesTtlSeconds() {
        return SERIES_TTL_MS / 1000;
    }

    public long structureTtlSeconds() {
        return STRUCTURE_TTL_MS / 1000;
    }

    public double dataflowFetchedAtEpochSeconds() {
        lock.lock();
        try {
            return dataflowFetchedAtMs / 1000.0;
        } finally {
            lock.unlock();
        }
    }

    public void invalidateCaches() {
        lock.lock();
        try {
            dataflowTreeCache = null;
            dataflowListCache = List.of();
            dataflowFetchedAtMs = 0L;
            seriesCache.clear();
            structureCache.clear();
        } finally {
            lock.unlock();
        }
    }

    public Map<String, Object> getCatalog(boolean forceRefresh) throws IOException, InterruptedException {
        long now = System.currentTimeMillis();
        lock.lock();
        try {
            if (!forceRefresh && dataflowTreeCache != null && (now - dataflowFetchedAtMs) < DATAFLOW_TTL_MS) {
                return new LinkedHashMap<>(dataflowTreeCache);
            }
        } finally {
            lock.unlock();
        }

        List<Map<String, String>> flows = fetchDataflowIndex();
        Map<String, Object> tree = buildDataflowTree(flows);
        lock.lock();
        try {
            dataflowTreeCache = new LinkedHashMap<>(tree);
            dataflowListCache = new ArrayList<>(flows);
            dataflowFetchedAtMs = now;
        } finally {
            lock.unlock();
        }
        return tree;
    }

    public Map<String, Object> getAvailabilitySummary() {
        return Map.of();
    }

    public Map<String, Object> getRefAreas() {
        try {
            String url = BIS_API_ROOT + "/codelist/BIS/CL_BIS_IF_REF_AREA/latest";
            HttpResponse<byte[]> response = getBinary(
                    url,
                    BIS_STRUCTURE_ACCEPT,
                    Duration.ofSeconds(45));
            if (response.statusCode() != 200) {
                return Map.of("areas", List.of());
            }
            List<Map<String, String>> areas = parseRefAreaCodelist(response.body());
            return Map.of("areas", areas);
        } catch (Exception ex) {
            log.debug("BIS ref areas fetch failed: {}", ex.getMessage());
            return Map.of("areas", List.of());
        }
    }

    public Map<String, Object> getSeriesForDataflow(
            String dataflow,
            boolean forceRefresh,
            boolean availabilityOnly,
            Set<String> refAreas)
            throws IOException, InterruptedException {
        String flow = validateFlowOrThrow(dataflow);
        Set<String> normRefAreas = normalizeRefAreas(refAreas);
        String cacheKey = flow + "|avail:" + availabilityOnly + "|areas:" + String.join(",", normRefAreas);
        long now = System.currentTimeMillis();

        lock.lock();
        try {
            CacheEntry cached = seriesCache.get(cacheKey);
            if (!forceRefresh && cached != null && (now - cached.cachedAtMs()) < SERIES_TTL_MS) {
                return new LinkedHashMap<>(cached.payload());
            }
        } finally {
            lock.unlock();
        }

        DataflowSeriesPayload payload = fetchSeriesPayload(flow, normRefAreas);
        Map<String, Object> out = payload.payload();

        lock.lock();
        try {
            seriesCache.put(cacheKey, new CacheEntry(now, new LinkedHashMap<>(out)));
        } finally {
            lock.unlock();
        }
        return out;
    }

    public Map<String, Object> getDataflowStructure(String flowRaw, boolean refresh)
            throws IOException, InterruptedException {
        FlowIdentifier flow = parseFlowIdentifier(validateFlowOrThrow(flowRaw));
        StructureCacheEntry cached = null;
        long now = System.currentTimeMillis();

        lock.lock();
        try {
            cached = structureCache.get(flow.flowRef());
            if (!refresh && cached != null && (now - cached.cachedAtMs()) < STRUCTURE_TTL_MS) {
                return structurePayload(flow, cached.xmlBytes(), cached.contentType(), true);
            }
        } finally {
            lock.unlock();
        }

        byte[] xml = fetchStructureXml(flow);
        lock.lock();
        try {
            structureCache.put(flow.flowRef(), new StructureCacheEntry(now, xml, BIS_STRUCTURE_ACCEPT));
        } finally {
            lock.unlock();
        }
        return structurePayload(flow, xml, BIS_STRUCTURE_ACCEPT, false);
    }

    public Map<String, Object> getDataflowDimensions(String flowRaw, boolean refresh)
            throws IOException, InterruptedException {
        FlowIdentifier flow = parseFlowIdentifier(validateFlowOrThrow(flowRaw));
        StructureCacheEntry entry = null;
        long now = System.currentTimeMillis();

        lock.lock();
        try {
            entry = structureCache.get(flow.flowRef());
            if (refresh || entry == null || (now - entry.cachedAtMs()) >= STRUCTURE_TTL_MS) {
                entry = null;
            }
        } finally {
            lock.unlock();
        }

        boolean cached;
        byte[] xml;
        if (entry != null) {
            xml = entry.xmlBytes();
            cached = true;
        } else {
            xml = fetchStructureXml(flow);
            cached = false;
            lock.lock();
            try {
                structureCache.put(flow.flowRef(), new StructureCacheEntry(now, xml, BIS_STRUCTURE_ACCEPT));
            } finally {
                lock.unlock();
            }
        }

        Map<String, Object> parsed = parseDimensionsFromStructure(xml, flow.flowRef());
        Map<String, Object> out = new LinkedHashMap<>(parsed);
        out.put("flow", flow.flowRef());
        out.put("agency", flow.agency());
        out.put("resource", flow.resource());
        out.put("version", flow.version());
        out.put("cached", cached);
        out.put(
                "bis_dimensions_notice_cs",
                cached ? "Parsováno z cache BIS struktury." : "Staženo z BIS Stats API.");
        return out;
    }

    public Map<String, Object> proxyDataflowsFlat() throws IOException, InterruptedException {
        List<Map<String, String>> flows = fetchDataflowIndex();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("external_data_base", BIS_API_ROOT + "/data/{flow}/{key}/all");
        out.put("stats_api_root", BIS_API_ROOT);
        out.put("count", flows.size());
        out.put("dataflows", flows);
        return out;
    }

    public Map<String, Object> proxyDataPreview(String flowRaw, String keyRaw, Integer lastNObservations)
            throws IOException, InterruptedException {
        String flow = validateFlowOrThrow(flowRaw);
        String key = validateKeyOrThrow(keyRaw);
        Integer boundedN = normalizeLastN(lastNObservations);

        Map<String, String> query = new LinkedHashMap<>();
        query.put("detail", "dataonly");
        if (boundedN != null) {
            query.put("lastNObservations", String.valueOf(boundedN));
        }
        String url = buildBisDataUrl(flow, key, query);
        HttpResponse<String> response = getText(url, BIS_GENERICDATA_ACCEPT, Duration.ofSeconds(90));

        String body = response.body() != null ? response.body() : "";
        String snippet = body.length() > 8000 ? body.substring(0, 8000) : body;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("request_url_masked", url.split("\\?")[0]);
        out.put("status_code", response.statusCode());
        out.put("content_type", response.headers().firstValue("Content-Type").orElse("").trim());
        out.put("body_snippet", snippet);
        out.put("body_length", body.length());
        out.put("normalize_warnings", List.of());
        return out;
    }

    public static String composeCatalogSetId(String flow, String key) {
        return "BIS|" + validateFlowOrThrow(flow) + "|" + validateKeyOrThrow(key);
    }

    public static ParsedSetId parseSetId(String rawSetId) {
        String setId = stringOrBlank(rawSetId);
        if (setId.startsWith("BIS|")) {
            String rest = setId.substring(4);
            int pivot = rest.lastIndexOf('|');
            if (pivot <= 0 || pivot >= rest.length() - 1) {
                throw new IllegalArgumentException("Neplatný BIS set_id.");
            }
            String flow = validateFlowOrThrow(rest.substring(0, pivot));
            String key = validateKeyOrThrow(rest.substring(pivot + 1));
            return new ParsedSetId(flow, key);
        }
        int slash = setId.indexOf('/');
        if (slash > 0 && slash < setId.length() - 1) {
            String flow = validateFlowOrThrow(setId.substring(0, slash));
            String key = validateKeyOrThrow(setId.substring(slash + 1));
            return new ParsedSetId(flow, key);
        }
        throw new IllegalArgumentException("Neplatný BIS set_id.");
    }

    public static Map<String, Object> normalizeBisQueryParams(Map<String, Object> payload, String queryContext) {
        Map<String, Object> safePayload = payload != null ? payload : Map.of();
        Object rawQp = safePayload.get("query_params");
        Map<String, Object> qpIn = rawQp instanceof Map<?, ?> map ? toStringObjectMap(map) : Map.of();
        Map<String, Object> out = new LinkedHashMap<>();

        String detail = stringOrBlank(qpIn.getOrDefault("detail", qpIn.get("Detail")));
        out.put("detail", detail.isBlank() ? "dataonly" : detail);

        for (String key : List.of("startPeriod", "endPeriod", "firstNObservations", "lastNObservations")) {
            String value = stringOrBlank(qpIn.getOrDefault(key, safePayload.get(key)));
            if (!value.isBlank()) {
                out.put(key, value);
            }
        }

        if ("preview".equals(queryContext)
                && !out.containsKey("startPeriod")
                && !out.containsKey("endPeriod")
                && !out.containsKey("firstNObservations")
                && !out.containsKey("lastNObservations")) {
            // 120 tise oriznulo "cele obdobi" na poslednich 120 pozorovani, kdyz nikdo vyslovne
            // nezadal rozsah - stejna trida chyby jako u yahoo_finance.
            out.put("lastNObservations", "20000");
        }
        return out;
    }

    public static String buildBisDataUrl(String flow, String key, Map<String, String> queryParams) {
        String flowSeg = encodePathSegment(validateFlowOrThrow(flow));
        String keySeg = encodePathSegment(validateKeyOrThrow(key));
        List<String> pairs = new ArrayList<>();
        if (queryParams != null) {
            for (Map.Entry<String, String> entry : queryParams.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                String qk = stringOrBlank(entry.getKey());
                if (qk.isBlank() || (!ALLOWED_PREVIEW_QUERY.contains(qk) && !"skip".equalsIgnoreCase(qk))) {
                    continue;
                }
                String qv = stringOrBlank(entry.getValue());
                if (qv.isBlank()) {
                    continue;
                }
                pairs.add(urlEncode(qk) + "=" + urlEncode(qv));
            }
        }
        String query = pairs.isEmpty() ? "" : "?" + String.join("&", pairs);
        return BIS_API_ROOT + "/data/" + flowSeg + "/" + keySeg + "/all" + query;
    }

    private DataflowSeriesPayload fetchSeriesPayload(String flow, Set<String> refAreas)
            throws IOException, InterruptedException {
        List<Map<String, String>> flowIndex = fetchDataflowIndex();
        String flowTitle = flow;
        for (Map<String, String> row : flowIndex) {
            if (flow.equalsIgnoreCase(stringOrBlank(row.get("id")))) {
                flowTitle = stringOrBlank(row.get("title"));
                if (flowTitle.isBlank()) {
                    flowTitle = flow;
                }
                break;
            }
        }

        String csvUrl = BIS_API_ROOT + "/data/" + encodePathSegment(flow) + "/all?format=csvdata&detail=serieskeysonly";
        HttpResponse<String> response = getText(csvUrl, "text/csv", Duration.ofSeconds(120));
        if (response.statusCode() != 200) {
            throw new IOException("BIS series list HTTP " + response.statusCode());
        }
        List<String[]> rows = parseCsv(response.body());
        if (rows.isEmpty()) {
            return new DataflowSeriesPayload(buildEmptySeriesTree(flow, flowTitle), null);
        }
        String[] header = rows.get(0);
        List<String[]> dataRows = rows.subList(1, rows.size());
        int refAreaIdx = findRefAreaIndex(header);

        Map<String, Integer> areaCounts = new HashMap<>();
        int totalRows = 0;
        for (String[] row : dataRows) {
            if (row.length != header.length) {
                continue;
            }
            totalRows += 1;
            String area = resolveAreaCode(row, refAreaIdx);
            areaCounts.put(area, areaCounts.getOrDefault(area, 0) + 1);
        }

        if (!refAreas.isEmpty()) {
            areaCounts = filterAreaCounts(areaCounts, refAreas);
            totalRows = areaCounts.values().stream().mapToInt(Integer::intValue).sum();
        }

        if (totalRows > SERIES_LAZY_COUNTRY_THRESHOLD) {
            return new DataflowSeriesPayload(
                    buildLazyCountrySeriesTree(flow, flowTitle, areaCounts, totalRows),
                    null);
        }

        List<Map<String, Object>> leaves = new ArrayList<>();
        for (String[] row : dataRows) {
            if (row.length != header.length) {
                continue;
            }
            String area = resolveAreaCode(row, refAreaIdx);
            if (!refAreas.isEmpty() && !refAreas.contains(area)) {
                continue;
            }
            String key = joinSeriesKey(row);
            if (key.isBlank()) {
                continue;
            }
            String setId = composeCatalogSetId(flow, key);
            Map<String, Object> leaf = new LinkedHashMap<>();
            leaf.put("id", setId);
            leaf.put("set_id", setId);
            leaf.put("name", area);
            leaf.put("full_path", "BIS > " + flowTitle + " > " + key);
            leaf.put("kind", "selection");
            leaf.put("dataset_code", flow);
            leaf.put("dataset_name", flowTitle);
            leaf.put("bis_dataflow", flow);
            leaf.put("bis_series_key", key);
            leaf.put("bis_catalog_set_id", setId);
            leaf.put("period", "dle řady");
            leaf.put("territory", area);
            leaf.put("ref_area", area);
            leaf.put("last_update", "");
            leaves.add(leaf);
        }

        Map<String, List<Map<String, Object>>> byArea = new HashMap<>();
        for (Map<String, Object> leaf : leaves) {
            String area = stringOrBlank(leaf.get("ref_area"));
            if (area.isBlank()) {
                area = "_OTHER_";
            }
            byArea.computeIfAbsent(area, ignored -> new ArrayList<>()).add(leaf);
        }

        List<Map<String, Object>> children = new ArrayList<>();
        for (String area : sortAreas(byArea.keySet())) {
            List<Map<String, Object>> areaSets = byArea.get(area);
            areaSets.sort(Comparator.comparing(v -> stringOrBlank(v.get("bis_series_key")).toLowerCase(Locale.ROOT)));
            Map<String, Object> child = new LinkedHashMap<>();
            child.put("name", area);
            child.put("path", "BIS::" + flow + " > " + area);
            child.put("children", List.of());
            child.put("sets", areaSets);
            children.add(child);
        }

        Map<String, Object> rootNode = new LinkedHashMap<>();
        rootNode.put("name", flowTitle);
        rootNode.put("path", "BIS::" + flow);
        rootNode.put("children", children);
        rootNode.put("sets", List.of());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("categories", List.of(rootNode));
        out.put("total_sets", leaves.size());
        out.put("errors", List.of());
        out.put("dataflow_id", flow);
        out.put("source", "live");
        out.put("catalog_mode", "browse");
        out.put("index_filtered", !refAreas.isEmpty());

        if (children.size() == 1 && !refAreas.isEmpty()) {
            out.put("country_node", children.get(0));
        }
        return new DataflowSeriesPayload(out, null);
    }

    private Map<String, Object> buildEmptySeriesTree(String flow, String flowTitle) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("name", flowTitle);
        node.put("path", "BIS::" + flow);
        node.put("children", List.of());
        node.put("sets", List.of());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("categories", List.of(node));
        out.put("total_sets", 0);
        out.put("errors", List.of());
        out.put("dataflow_id", flow);
        out.put("source", "live");
        out.put("catalog_mode", "browse");
        out.put("index_filtered", false);
        return out;
    }

    private Map<String, Object> buildLazyCountrySeriesTree(
            String flow,
            String flowTitle,
            Map<String, Integer> areaCounts,
            int totalRows) {
        List<Map<String, Object>> children = new ArrayList<>();
        for (String area : sortAreas(areaCounts.keySet())) {
            Map<String, Object> child = new LinkedHashMap<>();
            child.put("name", area);
            child.put("path", "BIS::" + flow + " > " + area);
            child.put("children", List.of());
            child.put("sets", List.of());
            child.put("bis_lazy_country", true);
            child.put("ref_area", area);
            child.put("series_count", areaCounts.getOrDefault(area, 0));
            children.add(child);
        }

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("name", flowTitle);
        node.put("path", "BIS::" + flow);
        node.put("children", children);
        node.put("sets", List.of());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("categories", List.of(node));
        out.put("total_sets", totalRows);
        out.put("errors", List.of());
        out.put("dataflow_id", flow);
        out.put("source", "live");
        out.put("catalog_mode", "countries_lazy");
        out.put("index_filtered", false);
        return out;
    }

    private List<Map<String, String>> fetchDataflowIndex() throws IOException, InterruptedException {
        List<String> urls = List.of(
                BIS_API_ROOT + "/dataflow/BIS/all/latest?references=none&detail=allstubs",
                BIS_API_ROOT + "/dataflow/all/all/latest?references=none&detail=allstubs");
        for (String url : urls) {
            HttpResponse<byte[]> response = getBinary(url, BIS_STRUCTURE_ACCEPT, Duration.ofSeconds(90));
            if (response.statusCode() != 200) {
                continue;
            }
            List<Map<String, String>> flows = parseDataflowStubXml(response.body());
            if (!flows.isEmpty()) {
                return flows;
            }
        }
        throw new IOException("Nepodařilo se načíst seznam BIS dataflow.");
    }

    private static Map<String, Object> buildDataflowTree(List<Map<String, String>> flows) {
        Map<String, List<Map<String, String>>> buckets = new HashMap<>();
        for (Map<String, String> row : flows) {
            String id = stringOrBlank(row.get("id"));
            if (id.isBlank()) {
                continue;
            }
            String title = stringOrBlank(row.get("title"));
            if (title.isBlank()) {
                title = id;
            }
            String first = title.substring(0, 1).toUpperCase(Locale.ROOT);
            String bucket = first.matches("[A-Z0-9]") ? first : "#";
            Map<String, String> normalized = new LinkedHashMap<>(row);
            normalized.put("title", title);
            buckets.computeIfAbsent(bucket, ignored -> new ArrayList<>()).add(normalized);
        }

        List<String> letters = new ArrayList<>(buckets.keySet());
        letters.sort(String::compareTo);

        List<Map<String, Object>> letterNodes = new ArrayList<>();
        int total = 0;
        for (String letter : letters) {
            List<Map<String, String>> items = buckets.get(letter);
            items.sort(Comparator.comparing(v -> stringOrBlank(v.get("title")).toLowerCase(Locale.ROOT)));

            List<Map<String, Object>> sets = new ArrayList<>();
            for (Map<String, String> item : items) {
                String id = stringOrBlank(item.get("id"));
                String title = stringOrBlank(item.get("title"));
                Map<String, Object> set = new LinkedHashMap<>();
                set.put("id", id + "||DATAFLOW");
                set.put("set_id", id + "||DATAFLOW");
                set.put("name", title);
                set.put("full_path", "BIS > " + letter + " > " + title);
                set.put("kind", "dataflow");
                set.put("item_kind", "dataflow");
                set.put("bis_dataflow", id);
                set.put("period", "");
                set.put("territory", "BIS");
                set.put("last_update", "");
                sets.add(set);
            }
            total += sets.size();

            Map<String, Object> letterNode = new LinkedHashMap<>();
            letterNode.put("name", letter);
            letterNode.put("path", "BIS > " + letter);
            letterNode.put("children", List.of());
            letterNode.put("sets", sets);
            letterNodes.add(letterNode);
        }

        Map<String, Object> rootNode = new LinkedHashMap<>();
        rootNode.put("name", "BIS — datové toky");
        rootNode.put("path", "BIS");
        rootNode.put("children", letterNodes);
        rootNode.put("sets", List.of());

        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("categories", List.of(rootNode));
        tree.put("total_sets", total);
        tree.put("source", "live");
        tree.put("mode", "dataflows_only");
        return tree;
    }

    private static List<Map<String, String>> parseDataflowStubXml(byte[] content) {
        List<Map<String, String>> out = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document doc = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(content));
            NodeList dataflows = doc.getElementsByTagNameNS("*", "Dataflow");
            for (int i = 0; i < dataflows.getLength(); i++) {
                Node node = dataflows.item(i);
                if (!(node instanceof Element el)) {
                    continue;
                }
                String id = stringOrBlank(el.getAttribute("id"));
                if (id.isBlank()) {
                    continue;
                }
                String version = stringOrBlank(el.getAttribute("version"));
                if (version.isBlank()) {
                    version = "latest";
                }
                String title = id;
                NodeList names = el.getElementsByTagNameNS("*", "Name");
                if (names.getLength() > 0) {
                    title = stringOrBlank(names.item(0).getTextContent());
                    if (title.isBlank()) {
                        title = id;
                    }
                }
                Map<String, String> row = new LinkedHashMap<>();
                row.put("id", id);
                row.put("version", version);
                row.put("title", title);
                out.add(row);
            }
            out.sort(Comparator.comparing(v -> stringOrBlank(v.get("title")).toLowerCase(Locale.ROOT)));
        } catch (Exception ex) {
            log.debug("BIS dataflow xml parse failed: {}", ex.getMessage());
            return List.of();
        }
        return out;
    }

    private byte[] fetchStructureXml(FlowIdentifier flow) throws IOException, InterruptedException {
        String agency = encodePathSegment(flow.agency());
        String resource = encodePathSegment(flow.resource());
        String version = encodePathSegment(flow.version());

        List<String> urls = List.of(
                BIS_API_ROOT + "/datastructure/" + agency + "/" + resource + "/" + version + "?references=all&detail=referencepartial",
                BIS_API_ROOT + "/dataflow/" + agency + "/" + resource + "/" + version + "?references=all&detail=referencepartial");

        for (String url : urls) {
            HttpResponse<byte[]> response = getBinary(url, BIS_STRUCTURE_ACCEPT, Duration.ofSeconds(20));
            if (response.statusCode() == 200 && response.body() != null && response.body().length > 0) {
                return response.body();
            }
        }
        throw new IOException("Nepodařilo se načíst strukturu BIS dataflow.");
    }

    private Map<String, Object> structurePayload(
            FlowIdentifier flow,
            byte[] xmlBytes,
            String contentType,
            boolean cached) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("flow", flow.flowRef());
        out.put("agency", flow.agency());
        out.put("resource", flow.resource());
        out.put("version", flow.version());
        out.put("cached", cached);
        out.put("ttl_seconds", structureTtlSeconds());
        out.put("content_type", contentType != null ? contentType : BIS_STRUCTURE_ACCEPT);
        out.put("structure_xml_base64", Base64.getEncoder().encodeToString(xmlBytes));
        out.put("structure_byte_length", xmlBytes.length);
        out.put(
                "bis_structure_notice_cs",
                cached ? "Struktura z cache BIS (TTL 7 dnů)." : "Struktura z BIS Stats API uložena do cache.");
        return out;
    }

    private static Map<String, Object> parseDimensionsFromStructure(byte[] xmlBytes, String flowRef) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document doc = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xmlBytes));

            String flowName = flowRef;
            FlowIdentifier fid = parseFlowIdentifier(flowRef);
            String resource = fid.resource();

            NodeList dataflows = doc.getElementsByTagNameNS("*", "Dataflow");
            for (int i = 0; i < dataflows.getLength(); i++) {
                if (!(dataflows.item(i) instanceof Element df)) {
                    continue;
                }
                if (!resource.equals(stringOrBlank(df.getAttribute("id")))) {
                    continue;
                }
                NodeList names = df.getElementsByTagNameNS("*", "Name");
                if (names.getLength() > 0) {
                    String candidate = stringOrBlank(names.item(0).getTextContent());
                    if (!candidate.isBlank()) {
                        flowName = candidate;
                    }
                }
                break;
            }

            Map<String, Element> codelists = new HashMap<>();
            NodeList codelistNodes = doc.getElementsByTagNameNS("*", "Codelist");
            for (int i = 0; i < codelistNodes.getLength(); i++) {
                if (!(codelistNodes.item(i) instanceof Element cl)) {
                    continue;
                }
                String id = stringOrBlank(cl.getAttribute("id"));
                if (!id.isBlank()) {
                    codelists.put(id, cl);
                }
            }

            Element dataStructure = null;
            NodeList dsNodes = doc.getElementsByTagNameNS("*", "DataStructure");
            for (int i = 0; i < dsNodes.getLength(); i++) {
                if (dsNodes.item(i) instanceof Element ds) {
                    dataStructure = ds;
                    break;
                }
            }
            if (dataStructure == null) {
                throw new IOException("BIS structure neobsahuje DataStructure.");
            }

            List<Element> dimensions = new ArrayList<>();
            NodeList allDims = dataStructure.getElementsByTagNameNS("*", "Dimension");
            for (int i = 0; i < allDims.getLength(); i++) {
                if (allDims.item(i) instanceof Element dim) {
                    dimensions.add(dim);
                }
            }
            dimensions.sort(Comparator.comparingInt(BisCatalogService::dimensionPosition));

            List<Map<String, Object>> outDimensions = new ArrayList<>();
            for (int i = 0; i < dimensions.size(); i++) {
                Element dim = dimensions.get(i);
                String dimId = stringOrBlank(dim.getAttribute("id"));
                if (dimId.isBlank()) {
                    continue;
                }
                CodelistInfo cref = codelistRef(dim);
                List<Map<String, String>> values = List.of();
                boolean truncated = false;
                int total = 0;
                if (cref != null) {
                    Element codelist = codelists.get(cref.id());
                    if (codelist != null) {
                        CodelistValues parsedValues = parseCodelistValues(codelist, MAX_CODES_PER_DIMENSION);
                        values = parsedValues.values();
                        truncated = parsedValues.truncated();
                        total = parsedValues.total();
                    }
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", dimId);
                row.put("name", dimId.replace("_", " "));
                row.put("position", i);
                row.put("values", values);
                row.put("values_truncated", truncated);
                row.put("values_total", total);
                row.put("allow_wildcard", true);
                row.put("allow_multi_select", true);
                outDimensions.add(row);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("flowRef", flowRef);
            out.put("name", flowName);
            out.put("dimensions", outDimensions);
            out.put("can_build_series_key", !outDimensions.isEmpty());
            return out;
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("BIS strukturu se nepodařilo parsovat.", ex);
        }
    }

    private static int dimensionPosition(Element dim) {
        String raw = stringOrBlank(dim.getAttribute("position"));
        if (raw.isBlank()) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return Integer.MAX_VALUE;
        }
    }

    private static CodelistInfo codelistRef(Element dim) {
        NodeList localRepresentations = dim.getElementsByTagNameNS("*", "LocalRepresentation");
        if (localRepresentations.getLength() == 0) {
            return null;
        }
        Element localRep = (Element) localRepresentations.item(0);
        NodeList enumerations = localRep.getElementsByTagNameNS("*", "Enumeration");
        if (enumerations.getLength() == 0) {
            return null;
        }
        Element enumeration = (Element) enumerations.item(0);
        NodeList refs = enumeration.getElementsByTagNameNS("*", "Ref");
        if (refs.getLength() == 0) {
            return null;
        }
        Element ref = (Element) refs.item(0);
        String id = stringOrBlank(ref.getAttribute("id"));
        if (id.isBlank()) {
            return null;
        }
        String agency = stringOrBlank(ref.getAttribute("agencyID"));
        if (agency.isBlank()) {
            agency = "BIS";
        }
        String version = stringOrBlank(ref.getAttribute("version"));
        if (version.isBlank()) {
            version = "1.0";
        }
        return new CodelistInfo(agency, id, version);
    }

    private static CodelistValues parseCodelistValues(Element codelist, int maxCodes) {
        NodeList codes = codelist.getElementsByTagNameNS("*", "Code");
        int total = codes.getLength();
        List<Map<String, String>> values = new ArrayList<>();
        for (int i = 0; i < codes.getLength() && values.size() < maxCodes; i++) {
            if (!(codes.item(i) instanceof Element code)) {
                continue;
            }
            String id = stringOrBlank(code.getAttribute("id"));
            if (id.isBlank()) {
                continue;
            }
            String label = id;
            NodeList names = code.getElementsByTagNameNS("*", "Name");
            if (names.getLength() > 0) {
                String candidate = stringOrBlank(names.item(0).getTextContent());
                if (!candidate.isBlank()) {
                    label = candidate.length() > 260 ? candidate.substring(0, 260) : candidate;
                }
            }
            values.add(Map.of("id", id, "name", label));
        }
        values.sort(Comparator.comparing(v -> stringOrBlank(v.get("id"))));
        return new CodelistValues(values, total > maxCodes, total);
    }

    private static List<Map<String, String>> parseRefAreaCodelist(byte[] xmlBytes) {
        List<Map<String, String>> areas = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document doc = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xmlBytes));
            NodeList codes = doc.getElementsByTagNameNS("*", "Code");
            for (int i = 0; i < codes.getLength(); i++) {
                if (!(codes.item(i) instanceof Element code)) {
                    continue;
                }
                String id = stringOrBlank(code.getAttribute("id")).toUpperCase(Locale.ROOT);
                if (id.isBlank()) {
                    continue;
                }
                String label = id;
                NodeList names = code.getElementsByTagNameNS("*", "Name");
                if (names.getLength() > 0) {
                    String candidate = stringOrBlank(names.item(0).getTextContent());
                    if (!candidate.isBlank()) {
                        label = candidate;
                    }
                }
                areas.add(Map.of("id", id, "name", label));
            }
            areas.sort(Comparator.comparing(v -> stringOrBlank(v.get("id"))));
        } catch (Exception ex) {
            return List.of();
        }
        return areas;
    }

    private static Set<String> normalizeRefAreas(Set<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String value : raw) {
            String norm = stringOrBlank(value).toUpperCase(Locale.ROOT);
            if (!norm.isBlank()) {
                out.add(norm);
            }
        }
        return out;
    }

    private static int findRefAreaIndex(String[] header) {
        List<String> upper = new ArrayList<>();
        for (String h : header) {
            upper.add(stringOrBlank(h).toUpperCase(Locale.ROOT));
        }
        for (String key : List.of("REF_AREA", "REFAREA", "BORROWERS_CTY", "COUNTRY", "AREA", "L_REP_CTY", "REP_CTY")) {
            for (int i = 0; i < upper.size(); i++) {
                if (key.equals(upper.get(i))) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String resolveAreaCode(String[] row, int refAreaIdx) {
        if (refAreaIdx >= 0 && refAreaIdx < row.length) {
            String value = stringOrBlank(row[refAreaIdx]).toUpperCase(Locale.ROOT);
            return value.isBlank() ? "_OTHER_" : value;
        }
        return "_OTHER_";
    }

    private static Map<String, Integer> filterAreaCounts(Map<String, Integer> counts, Set<String> allowed) {
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (allowed.contains(entry.getKey())) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    private static List<String> sortAreas(Set<String> values) {
        List<String> out = new ArrayList<>(values);
        out.sort((a, b) -> {
            int pa = areaPriority(a);
            int pb = areaPriority(b);
            if (pa != pb) {
                return Integer.compare(pa, pb);
            }
            return a.compareToIgnoreCase(b);
        });
        return out;
    }

    private static int areaPriority(String area) {
        List<String> priority = List.of("XM", "U2", "7L", "XW", "5A", "5R", "4T", "4Z");
        int idx = priority.indexOf(stringOrBlank(area).toUpperCase(Locale.ROOT));
        return idx >= 0 ? idx : 1000;
    }

    private static String joinSeriesKey(String[] row) {
        List<String> parts = new ArrayList<>();
        for (String token : row) {
            parts.add(stringOrBlank(token));
        }
        return String.join(".", parts);
    }

    private static List<String[]> parseCsv(String text) {
        List<String[]> rows = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return rows;
        }
        String[] rawLines = text.split("\\r?\\n");
        for (String line : rawLines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            rows.add(parseCsvLine(line));
        }
        return rows;
    }

    private static String[] parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
                continue;
            }
            if (ch == ',' && !inQuotes) {
                out.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        out.add(current.toString().trim());
        return out.toArray(String[]::new);
    }

    private HttpResponse<String> getText(String url, String accept, Duration timeout) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("Accept", accept)
                .header("User-Agent", "banking-bi/1.0")
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> getBinary(String url, String accept, Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("Accept", accept)
                .header("User-Agent", "banking-bi/1.0")
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static FlowIdentifier parseFlowIdentifier(String flowRaw) {
        String flow = stringOrBlank(flowRaw);
        String[] parts = flow.split(",");
        List<String> compact = new ArrayList<>();
        for (String part : parts) {
            String trimmed = stringOrBlank(part);
            if (!trimmed.isBlank()) {
                compact.add(trimmed);
            }
        }
        if (compact.isEmpty()) {
            throw new IllegalArgumentException("Chybí kód dataflow.");
        }
        if (compact.size() == 1) {
            return new FlowIdentifier("BIS", compact.get(0), "latest", flow);
        }
        if (compact.size() == 2) {
            return new FlowIdentifier(compact.get(0), compact.get(1), "latest", flow);
        }
        return new FlowIdentifier(compact.get(0), compact.get(1), compact.get(2), flow);
    }

    private static String validateFlowOrThrow(String raw) {
        String flow = stringOrBlank(raw);
        if (flow.isBlank()) {
            throw new IllegalArgumentException("Pro BIS Stats API je povinný flow.");
        }
        if (flow.contains("\\") || flow.contains("#") || flow.contains("\n") || flow.contains("\r")) {
            throw new IllegalArgumentException("Neplatný BIS flow.");
        }
        for (String segment : flow.split(",")) {
            String trimmed = stringOrBlank(segment);
            if (trimmed.contains("..")) {
                throw new IllegalArgumentException("Neplatný BIS flow.");
            }
            if (!trimmed.isBlank() && !trimmed.matches("^[A-Za-z0-9_.,+/\\-]+$")) {
                throw new IllegalArgumentException("Neplatný BIS flow.");
            }
        }
        return flow;
    }

    private static String validateKeyOrThrow(String raw) {
        String key = stringOrBlank(raw);
        if (key.isBlank()) {
            throw new IllegalArgumentException("Pro BIS Stats API je povinný key.");
        }
        if (key.contains("\\") || key.contains("?") || key.contains("#") || key.contains("/")) {
            throw new IllegalArgumentException("Neplatný BIS key.");
        }
        if (key.contains("..")) {
            throw new IllegalArgumentException("Neplatný BIS key.");
        }
        if ("all".equalsIgnoreCase(key)) {
            return "all";
        }
        for (String part : key.split("\\.")) {
            if (part.isBlank()) {
                continue;
            }
            for (String token : part.split("\\+")) {
                if (token.isBlank()) {
                    continue;
                }
                if (!token.matches("^[A-Za-z0-9_.\\-]+$")) {
                    throw new IllegalArgumentException("Neplatný BIS key.");
                }
            }
        }
        return key;
    }

    private static Integer normalizeLastN(Integer raw) {
        if (raw == null) {
            return 12;
        }
        if (raw < 1) {
            return 1;
        }
        return Math.min(raw, 500);
    }

    private static String encodePathSegment(String value) {
        return urlEncode(stringOrBlank(value));
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String stringOrBlank(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    private static Map<String, Object> toStringObjectMap(Map<?, ?> input) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return out;
    }

    private record CacheEntry(long cachedAtMs, Map<String, Object> payload) {}

    private record StructureCacheEntry(long cachedAtMs, byte[] xmlBytes, String contentType) {}

    private record DataflowSeriesPayload(Map<String, Object> payload, String error) {}

    public record ParsedSetId(String flow, String key) {}

    private record FlowIdentifier(String agency, String resource, String version, String flowRef) {}

    private record CodelistInfo(String agency, String id, String version) {}

    private record CodelistValues(List<Map<String, String>> values, boolean truncated, int total) {}
}
