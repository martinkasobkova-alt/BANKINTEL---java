package cz.bankintel.sources.eurostat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.domain.entity.SourceEntity;
import cz.bankintel.repository.SourceRepository;
import cz.bankintel.util.BankIntelDataPaths;
import cz.bankintel.util.IdGenerator;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.GZIPInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

@Service
public class EurostatCatalogService {

    private static final Logger log = LoggerFactory.getLogger(EurostatCatalogService.class);
    private static final URI TOC_URL = URI.create("https://ec.europa.eu/eurostat/api/dissemination/catalogue/toc/xml?lang=en");
    private static final String EUROSTAT_BASE_URL = "https://ec.europa.eu/eurostat/api/dissemination/statistics/1.0/data";
    private static final long CACHE_TTL_MS = Duration.ofHours(12).toMillis();

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final SourceRepository sourceRepository;
    private final Path diskCachePath;

    private final ReentrantLock cacheLock = new ReentrantLock();
    private Map<String, Object> cachedTree;
    private Map<String, Map<String, Object>> cachedCodeIndex = Map.of();
    private long fetchedAtMs;
    private String catalogRescue;
    private boolean stale;
    private String warning;

    public EurostatCatalogService(ObjectMapper objectMapper, SourceRepository sourceRepository) {
        this.objectMapper = objectMapper;
        this.sourceRepository = sourceRepository;
        this.diskCachePath = BankIntelDataPaths.resolveDataFile("eurostat_catalog_disk_cache.json.gz");
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        hydrateFromDiskIfAvailable(false);
    }

    @PostConstruct
    void logStartup() {
        log.info(
                "Eurostat catalog disk cache path={} exists={}",
                diskCachePath.toAbsolutePath().normalize(),
                Files.isRegularFile(diskCachePath));
    }

    public Map<String, Object> getCatalogEnvelope() {
        cacheLock.lock();
        try {
            if (cachedTree != null && (System.currentTimeMillis() - fetchedAtMs) < CACHE_TTL_MS) {
                return envelope(cachedTree, true, false, catalogRescue, warning);
            }
            if (cachedTree != null) {
                return envelope(cachedTree, true, true, catalogRescue, warning);
            }
        } finally {
            cacheLock.unlock();
        }

        if (hydrateFromDiskIfAvailable(false)) {
            cacheLock.lock();
            try {
                boolean staleNow = (System.currentTimeMillis() - fetchedAtMs) >= CACHE_TTL_MS;
                return envelope(cachedTree, true, staleNow, catalogRescue, warning);
            } finally {
                cacheLock.unlock();
            }
        }

        try {
            ParseResult live = fetchLiveCatalog();
            applyCache(live.tree(), live.codeIndex(), System.currentTimeMillis(), null, false, null);
            return envelope(cachedTree, false, false, null, null);
        } catch (Exception ex) {
            log.warn("Eurostat live catalog fetch failed: {}", ex.getMessage());
        }

        cacheLock.lock();
        try {
            if (cachedTree != null) {
                return envelope(cachedTree, true, true, catalogRescue, warning);
            }
        } finally {
            cacheLock.unlock();
        }
        Map<String, Object> empty = Map.of("categories", List.of(), "total_sets", 0);
        return envelope(empty, false, true, "unavailable", "Katalog Eurostatu se teď nepodařilo načíst.");
    }

    public Map<String, Object> refreshCatalog() {
        try {
            ParseResult live = fetchLiveCatalog();
            applyCache(live.tree(), live.codeIndex(), System.currentTimeMillis(), null, false, null);
            return envelope(cachedTree, false, false, null, null);
        } catch (Exception ex) {
            log.error("Eurostat catalog refresh failed: {}", ex.getMessage());
            cacheLock.lock();
            try {
                if (cachedTree != null) {
                    return envelope(cachedTree, true, true, catalogRescue, warning);
                }
            } finally {
                cacheLock.unlock();
            }
            return Map.of(
                    "ok",
                    false,
                    "detail",
                    "Katalog Eurostatu není nyní dostupný. Zkuste to prosím později.");
        }
    }

    public Map<String, Object> addSourceFromCatalog(Map<String, Object> payload) {
        String code = sanitizeCode(payload.get("set_id"));
        if (code.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Neplatný kód datasetu Eurostat.");
        }

        ensureCodeIndexLoaded();
        Map<String, Object> matched = cachedCodeIndex.getOrDefault(code, Map.of());
        String shortName = toStringOrDefault(matched.get("name"), code);
        String fullPath = toStringOrDefault(matched.get("full_path"), shortName);
        String geo = toStringOrDefault(payload.get("geo"), "").trim().toUpperCase(Locale.ROOT);

        String defaultName = "Eurostat · " + shortName + " (" + code + ")";
        if (!geo.isBlank()) {
            defaultName = defaultName + " [" + geo + "]";
        }
        String displayName = toStringOrDefault(payload.get("name"), "").trim();
        if (displayName.isBlank()) {
            displayName = defaultName;
        }

        if (sourceRepository.existsByName(displayName)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Zdroj '" + displayName + "' už existuje.");
        }

        Map<String, Object> queryParams = new LinkedHashMap<>();
        queryParams.put("format", "JSON");
        queryParams.put("lang", "EN");
        if (!geo.isBlank()) {
            queryParams.put("geo", geo);
        }
        Object extra = payload.get("query_params");
        if (extra instanceof Map<?, ?> extraMap) {
            for (Map.Entry<?, ?> entry : extraMap.entrySet()) {
                String key = entry.getKey() != null ? entry.getKey().toString().trim() : "";
                if (!key.isBlank() && entry.getValue() != null) {
                    queryParams.put(key, entry.getValue());
                }
            }
        }

        SourceEntity entity = new SourceEntity();
        entity.setId(IdGenerator.newId());
        entity.setName(displayName);
        entity.setSourceType("eurostat");
        entity.setBaseUrl(EUROSTAT_BASE_URL);
        entity.setEndpoint("/" + code);
        entity.setMethod("GET");
        entity.setAuthType("none");
        entity.setRefreshIntervalMinutes(intField(payload, "refresh_interval_minutes", 1440));
        entity.setActive(boolField(payload, "active", true));
        entity.setDatasetName(displayName);
        entity.setConnectorConfig(Map.of(
                "headers", Map.of("Accept", "application/json"),
                "query_params", queryParams,
                "credentials", Map.of()));
        sourceRepository.save(entity);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", entity.getId());
        out.put("name", displayName);
        out.put("set_id", code);
        out.put("full_path", fullPath);
        return out;
    }

    private void ensureCodeIndexLoaded() {
        cacheLock.lock();
        try {
            if (!cachedCodeIndex.isEmpty()) {
                return;
            }
        } finally {
            cacheLock.unlock();
        }
        if (hydrateFromDiskIfAvailable(false)) {
            return;
        }
        try {
            ParseResult live = fetchLiveCatalog();
            applyCache(live.tree(), live.codeIndex(), System.currentTimeMillis(), null, false, null);
        } catch (Exception ex) {
            log.warn("Eurostat code index preload failed: {}", ex.getMessage());
        }
    }

    private ParseResult fetchLiveCatalog() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder().uri(TOC_URL).timeout(Duration.ofSeconds(25)).GET().build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return parseTree(response.body());
    }

    private boolean hydrateFromDiskIfAvailable(boolean force) {
        cacheLock.lock();
        try {
            if (!force && cachedTree != null) {
                return true;
            }
        } finally {
            cacheLock.unlock();
        }

        if (!Files.isRegularFile(diskCachePath)) {
            log.debug("Eurostat disk cache not found: {}", diskCachePath);
            return false;
        }
        try (InputStream in = Files.newInputStream(diskCachePath);
                GZIPInputStream gzip = new GZIPInputStream(in)) {
            Map<String, Object> payload = objectMapper.readValue(gzip, new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            Map<String, Object> tree = payload.get("tree") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
            Map<String, Map<String, Object>> codeIndex = parseCodeIndex(payload.get("raw_codes"));
            if (tree == null || tree.isEmpty()) {
                return false;
            }
            long diskFetchedAtMs = (long) (doubleField(payload, "fetched_at", 0.0) * 1000.0);
            applyCache(tree, codeIndex, diskFetchedAtMs, "disk_cache", false, null);
            log.info("Eurostat catalog hydrated from disk path={} total_sets={}", diskCachePath, tree.get("total_sets"));
            return true;
        } catch (Exception ex) {
            log.warn("Eurostat disk cache read failed ({}): {}", diskCachePath, ex.getMessage());
            return false;
        }
    }

    private void applyCache(
            Map<String, Object> tree,
            Map<String, Map<String, Object>> codeIndex,
            long fetchedMs,
            String rescue,
            boolean staleFlag,
            String warn) {
        cacheLock.lock();
        try {
            cachedTree = tree;
            cachedCodeIndex = codeIndex != null ? codeIndex : Map.of();
            fetchedAtMs = fetchedMs > 0 ? fetchedMs : System.currentTimeMillis();
            catalogRescue = rescue;
            stale = staleFlag;
            warning = warn;
        } finally {
            cacheLock.unlock();
        }
    }

    private ParseResult parseTree(byte[] xmlBytes) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document document = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xmlBytes));
            Element root = document.getDocumentElement();
            Map<String, Map<String, Object>> codeIndex = new LinkedHashMap<>();
            List<Map<String, Object>> categories = new ArrayList<>();
            for (Element child : childElements(root)) {
                WalkResult parsed = walk(child, List.of(), codeIndex);
                if (parsed != null && parsed.kind() == NodeKind.BRANCH) {
                    categories.add(parsed.node());
                }
            }
            categories.sort(Comparator.comparing(m -> lowerName(m.get("name"))));
            Map<String, Object> tree = new LinkedHashMap<>();
            tree.put("categories", categories);
            tree.put("total_sets", codeIndex.size());
            return new ParseResult(tree, codeIndex);
        } catch (Exception ex) {
            throw new IOException("Failed to parse Eurostat TOC XML", ex);
        }
    }

    private WalkResult walk(Element element, List<String> pathParts, Map<String, Map<String, Object>> codeIndex) {
        String local = element.getLocalName() != null ? element.getLocalName() : element.getTagName();
        if ("branch".equals(local)) {
            String name = textByTag(element, "title", "en");
            if (name.isBlank()) {
                name = "(untitled)";
            }
            List<String> newPath = new ArrayList<>(pathParts);
            newPath.add(name);
            List<Map<String, Object>> children = new ArrayList<>();
            List<Map<String, Object>> sets = new ArrayList<>();
            Element childrenElement = firstChildByTag(element, "children");
            if (childrenElement != null) {
                for (Element child : childElements(childrenElement)) {
                    WalkResult parsed = walk(child, newPath, codeIndex);
                    if (parsed == null) {
                        continue;
                    }
                    if (parsed.kind() == NodeKind.BRANCH) {
                        children.add(parsed.node());
                    } else {
                        sets.add(parsed.set());
                    }
                }
            }
            children.sort(Comparator.comparing(m -> lowerName(m.get("name"))));
            sets.sort(Comparator.comparing(m -> lowerName(m.get("name"))));
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("name", name);
            node.put("path", String.join(" > ", newPath));
            node.put("children", children);
            node.put("sets", sets);
            return WalkResult.branch(node);
        }

        if ("leaf".equals(local)) {
            String kind = toStringOrDefault(element.getAttribute("type"), "").trim().toLowerCase(Locale.ROOT);
            if ("folder".equals(kind)) {
                return null;
            }
            String name = textByTag(element, "title", "en");
            if (name.isBlank()) {
                name = "(untitled)";
            }
            String code = textByTag(element, "code", "en").trim();
            if (code.isBlank()) {
                return null;
            }
            String lastUpdate = textByTag(element, "lastUpdate", "en");
            String fullPath = String.join(" > ", concat(pathParts, name));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", code);
            entry.put("set_id", code);
            entry.put("name", name);
            entry.put("full_path", fullPath);
            entry.put("kind", kind.isBlank() ? "dataset" : kind);
            entry.put("last_update", lastUpdate);
            codeIndex.put(code, entry);
            codeIndex.put(code.toLowerCase(Locale.ROOT), entry);
            return WalkResult.leaf(entry);
        }
        return null;
    }

    private static String textByTag(Element element, String tagName, String lang) {
        String preferred = "";
        String fallback = "";
        for (Element child : childElements(element)) {
            String local = child.getLocalName() != null ? child.getLocalName() : child.getTagName();
            if (!tagName.equals(local)) {
                continue;
            }
            String text = child.getTextContent() != null ? child.getTextContent().trim() : "";
            if (text.isBlank()) {
                continue;
            }
            String language = child.getAttribute("language");
            if (lang.equalsIgnoreCase(language)) {
                preferred = text;
                break;
            }
            if (fallback.isBlank()) {
                fallback = text;
            }
        }
        return !preferred.isBlank() ? preferred : fallback;
    }

    private static Element firstChildByTag(Element element, String tagName) {
        for (Element child : childElements(element)) {
            String local = child.getLocalName() != null ? child.getLocalName() : child.getTagName();
            if (tagName.equals(local)) {
                return child;
            }
        }
        return null;
    }

    private static List<Element> childElements(Element parent) {
        NodeList nodes = parent.getChildNodes();
        List<Element> out = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                out.add((Element) node);
            }
        }
        return out;
    }

    private Map<String, Object> envelope(
            Map<String, Object> tree,
            boolean cached,
            boolean staleFlag,
            String rescue,
            String warn) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.putAll(tree);
        out.put("fetched_at", fetchedAtMs / 1000.0);
        out.put("cached", cached);
        out.put("source", "eurostat");
        out.put("stale", staleFlag || stale);
        out.put("catalog_rescue", rescue);
        out.put("warning", warn);
        out.put("refresh_in_progress", false);
        if (Boolean.TRUE.equals(out.get("stale"))) {
            out.put("status", "degraded");
        }
        return out;
    }

    private static String sanitizeCode(Object raw) {
        String code = toStringOrDefault(raw, "").trim().toLowerCase(Locale.ROOT);
        if (code.isBlank()) {
            return "";
        }
        for (int i = 0; i < code.length(); i++) {
            char ch = code.charAt(i);
            if (!(Character.isLetterOrDigit(ch) || ch == '_')) {
                return "";
            }
        }
        return code;
    }

    private static int intField(Map<String, Object> payload, String key, int defaultValue) {
        Object raw = payload.get(key);
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(raw.toString().trim());
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    private static boolean boolField(Map<String, Object> payload, String key, boolean defaultValue) {
        Object raw = payload.get(key);
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Boolean b) {
            return b;
        }
        String text = raw.toString().trim().toLowerCase(Locale.ROOT);
        if (text.isBlank()) {
            return defaultValue;
        }
        return "1".equals(text) || "true".equals(text) || "yes".equals(text) || "on".equals(text);
    }

    private static double doubleField(Map<String, Object> payload, String key, double defaultValue) {
        Object raw = payload.get(key);
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(raw.toString().trim());
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    private static Map<String, Map<String, Object>> parseCodeIndex(Object rawCodes) {
        if (!(rawCodes instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (entry.getKey() == null || !(entry.getValue() instanceof Map<?, ?> valueMap)) {
                continue;
            }
            String key = entry.getKey().toString();
            @SuppressWarnings("unchecked")
            Map<String, Object> value = new LinkedHashMap<>((Map<String, Object>) valueMap);
            out.put(key, value);
            out.put(key.toLowerCase(Locale.ROOT), value);
        }
        return out;
    }

    private static String lowerName(Object name) {
        return toStringOrDefault(name, "").toLowerCase(Locale.ROOT);
    }

    private static List<String> concat(List<String> pathParts, String suffix) {
        List<String> out = new ArrayList<>(pathParts);
        out.add(suffix);
        return out;
    }

    private static String toStringOrDefault(Object value, String defaultValue) {
        return value == null ? defaultValue : value.toString();
    }

    private record ParseResult(Map<String, Object> tree, Map<String, Map<String, Object>> codeIndex) {}

    private enum NodeKind {
        BRANCH,
        LEAF
    }

    private record WalkResult(NodeKind kind, Map<String, Object> node, Map<String, Object> set) {
        static WalkResult branch(Map<String, Object> node) {
            return new WalkResult(NodeKind.BRANCH, node, null);
        }

        static WalkResult leaf(Map<String, Object> set) {
            return new WalkResult(NodeKind.LEAF, null, set);
        }
    }
}
