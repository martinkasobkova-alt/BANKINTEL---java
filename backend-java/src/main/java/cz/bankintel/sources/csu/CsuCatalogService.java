package cz.bankintel.sources.csu;
import cz.bankintel.util.BankIntelEnvVars;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.domain.entity.SourceEntity;
import cz.bankintel.repository.SourceRepository;
import cz.bankintel.util.IdGenerator;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CsuCatalogService {

    private static final Logger log = LoggerFactory.getLogger(CsuCatalogService.class);

    private static final String CATALOG_BASE = "https://data.csu.gov.cz/api/katalog/v1";
    private static final String DATASTAT_BASE_URL = "https://data.csu.gov.cz";
    private static final long CACHE_TTL_MS = Duration.ofHours(12).toMillis();
    private static final double DEFAULT_HTTP_TIMEOUT_SEC = envDouble("CSU_CATALOG_HTTP_TIMEOUT_SEC", 20.0);
    private static final double REFRESH_HTTP_TIMEOUT_SEC =
            envDouble("CSU_CATALOG_REFRESH_HTTP_TIMEOUT_SEC", 45.0);

    private static final List<PrefixGroup> PREFIX_GROUPS = List.of(
            new PrefixGroup("WCEN", "Ceny a inflace"),
            new PrefixGroup("CEN", "Ceny a inflace"),
            new PrefixGroup("MZD", "Mzdy a platy"),
            new PrefixGroup("OBY", "Obyvatelstvo"),
            new PrefixGroup("DEM", "Obyvatelstvo a demografie"),
            new PrefixGroup("ZAM", "Zaměstnanost a nezaměstnanost"),
            new PrefixGroup("VSPS", "Trh práce (VŠPS)"),
            new PrefixGroup("NUC", "Národní účty (HDP)"),
            new PrefixGroup("HDP", "Národní účty (HDP)"),
            new PrefixGroup("PRU", "Průmysl"),
            new PrefixGroup("STA", "Stavebnictví"),
            new PrefixGroup("BYT", "Bytová výstavba"),
            new PrefixGroup("VZD", "Vzdělávání"),
            new PrefixGroup("ZDR", "Zdravotnictví"),
            new PrefixGroup("ZEM", "Zemědělství a lesnictví"),
            new PrefixGroup("DOP", "Doprava"),
            new PrefixGroup("CRU", "Cestovní ruch"),
            new PrefixGroup("ZPR", "Životní prostředí"),
            new PrefixGroup("KRI", "Kriminalita a soudnictví"),
            new PrefixGroup("VOL", "Volby"),
            new PrefixGroup("VZO", "Zahraniční obchod"),
            new PrefixGroup("MAL", "Maloobchod"),
            new PrefixGroup("SLU", "Služby"),
            new PrefixGroup("RSO", "Regionální statistiky"),
            new PrefixGroup("ROS", "Regionální statistiky"),
            new PrefixGroup("VES", "Věda, výzkum a inovace"),
            new PrefixGroup("INF", "Informační společnost"),
            new PrefixGroup("ENE", "Energetika"),
            new PrefixGroup("FIN", "Finance a podnikání"),
            new PrefixGroup("SOZ", "Sociální zabezpečení"));

    private final ObjectMapper objectMapper;
    private final SourceRepository sourceRepository;
    private final HttpClient httpClient;

    private final ReentrantLock cacheLock = new ReentrantLock();
    private Map<String, Object> cachedTree;
    private Map<String, Map<String, Object>> cachedSelectionIndex = Map.of();
    private long fetchedAtMs;

    public CsuCatalogService(ObjectMapper objectMapper, SourceRepository sourceRepository) {
        this.objectMapper = objectMapper;
        this.sourceRepository = sourceRepository;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public Map<String, Object> getCatalogEnvelope() {
        long now = System.currentTimeMillis();
        cacheLock.lock();
        try {
            if (cachedTree != null && (now - fetchedAtMs) < CACHE_TTL_MS) {
                return envelope(cachedTree, true, false);
            }
        } finally {
            cacheLock.unlock();
        }

        try {
            loadCatalogIntoCache(false, DEFAULT_HTTP_TIMEOUT_SEC, true, true);
            return envelope(cachedTree, false, isStaleFetchedAt());
        } catch (IOException | InterruptedException ex) {
            log.warn("CSU catalog fetch failed: {}", ex.getMessage());
        }

        cacheLock.lock();
        try {
            if (cachedTree != null) {
                return envelope(cachedTree, true, true);
            }
        } finally {
            cacheLock.unlock();
        }

        throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Katalog ČSÚ se teď nepodařilo načíst. Zkuste to znovu později.");
    }

    public Map<String, Object> refreshCatalog() {
        try {
            loadCatalogIntoCache(true, REFRESH_HTTP_TIMEOUT_SEC, true, true);
            return envelope(cachedTree, false, isStaleFetchedAt());
        } catch (IOException | InterruptedException ex) {
            log.warn("CSU catalog refresh failed: {}", ex.getMessage());
        }

        cacheLock.lock();
        try {
            if (cachedTree != null) {
                return envelope(cachedTree, true, true);
            }
        } finally {
            cacheLock.unlock();
        }

        throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Katalog ČSÚ se teď nepodařilo načíst. Zkuste to znovu později.");
    }

    public Map<String, Object> addSourceFromCatalog(Map<String, Object> payload) {
        String code = sanitizeSelectionCode(payload.get("set_id"));
        if (code.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Neplatný kód výběru ČSÚ.");
        }

        ensureSelectionIndexLoaded();

        Map<String, Object> matched = null;
        cacheLock.lock();
        try {
            matched = cachedSelectionIndex.get(code);
        } finally {
            cacheLock.unlock();
        }

        String shortName = matched != null ? stringOrBlank(matched.get("name")) : code;
        if (shortName.isBlank()) {
            shortName = code;
        }
        String fullPath = matched != null ? stringOrBlank(matched.get("full_path")) : code;
        if (fullPath.isBlank()) {
            fullPath = code;
        }
        String datasetCode = matched != null ? stringOrBlank(matched.get("dataset_code")) : "";

        String displayName = stringOrBlank(payload.get("name"));
        if (displayName.isBlank()) {
            displayName = "ČSÚ · " + shortName + " (" + code + ")";
        }
        if (sourceRepository.existsByName(displayName)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Zdroj '" + displayName + "' už existuje.");
        }

        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("Accept", "text/csv");
        headers.put("Accept-Language", "cs");

        Map<String, Object> queryParams = new LinkedHashMap<>();
        queryParams.put("format", "CSV");

        Map<String, Object> connectorConfig = new LinkedHashMap<>();
        connectorConfig.put("headers", headers);
        connectorConfig.put("query_params", queryParams);
        connectorConfig.put("credentials", Map.of());
        connectorConfig.put("csu_selection_code", code);
        connectorConfig.put("csu_dataset_code", datasetCode);
        connectorConfig.put("csu_full_path", fullPath);

        SourceEntity source = new SourceEntity();
        source.setId(IdGenerator.newId());
        source.setName(displayName);
        source.setSourceType("csu");
        source.setBaseUrl(DATASTAT_BASE_URL);
        source.setEndpoint("/api/dotaz/v1/data/vybery/" + code);
        source.setMethod("GET");
        source.setAuthType("none");
        source.setRefreshIntervalMinutes(intField(payload, "refresh_interval_minutes", 1440));
        source.setActive(boolField(payload, "active", true));
        source.setDatasetName(displayName);
        source.setConnectorConfig(connectorConfig);
        sourceRepository.save(source);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", source.getId());
        out.put("name", displayName);
        out.put("set_id", code);
        out.put("full_path", fullPath);
        return out;
    }

    private void ensureSelectionIndexLoaded() {
        cacheLock.lock();
        try {
            if (cachedSelectionIndex != null && !cachedSelectionIndex.isEmpty()) {
                return;
            }
        } finally {
            cacheLock.unlock();
        }
        try {
            loadCatalogIntoCache(false, DEFAULT_HTTP_TIMEOUT_SEC, true, true);
            return;
        } catch (IOException | InterruptedException ex) {
            log.warn("CSU selection index preload failed: {}", ex.getMessage());
        }

        cacheLock.lock();
        try {
            if (cachedSelectionIndex == null || cachedSelectionIndex.isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Katalog ČSÚ se teď nepodařilo načíst. Zkuste to znovu později.");
            }
        } finally {
            cacheLock.unlock();
        }
    }

    private void loadCatalogIntoCache(
            boolean force,
            double timeoutSec,
            boolean raiseHttpErrors,
            boolean staleOnError)
            throws IOException, InterruptedException {
        long now = System.currentTimeMillis();
        cacheLock.lock();
        try {
            if (!force && cachedTree != null && (now - fetchedAtMs) < CACHE_TTL_MS) {
                return;
            }
        } finally {
            cacheLock.unlock();
        }

        List<Map<String, Object>> selections;
        try {
            selections = fetchSelections(timeoutSec);
        } catch (IOException | InterruptedException ex) {
            if (staleOnError) {
                cacheLock.lock();
                try {
                    if (cachedTree != null) {
                        return;
                    }
                } finally {
                    cacheLock.unlock();
                }
            }
            if (raiseHttpErrors) {
                throw ex;
            }
            return;
        }

        BuildResult built = buildTree(selections);
        cacheLock.lock();
        try {
            cachedTree = built.tree();
            cachedSelectionIndex = built.selectionIndex();
            fetchedAtMs = now;
        } finally {
            cacheLock.unlock();
        }
    }

    private List<Map<String, Object>> fetchSelections(double timeoutSec) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(CATALOG_BASE + "/vybery"))
                .timeout(Duration.ofMillis((long) (Math.max(5.0, timeoutSec) * 1000)))
                .header("Accept", "application/json")
                .header("Accept-Language", "cs")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode());
        }

        Object raw = objectMapper.readValue(response.body(), new TypeReference<>() {});
        if (!(raw instanceof List<?> list)) {
            throw new IOException("unexpected CSU catalog JSON shape");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) map;
                out.add(cast);
            }
        }
        return out;
    }

    private static BuildResult buildTree(List<Map<String, Object>> selections) {
        Map<String, Map<String, Object>> selectionIndex = new LinkedHashMap<>();
        Map<String, Map<String, DatasetBucket>> grouped = new LinkedHashMap<>();

        for (Map<String, Object> entry : selections) {
            Map<String, Object> sada = mapField(entry, "sada");
            Map<String, Object> vyber = mapField(entry, "vyber");

            String datasetCode = stringOrBlank(sada.get("kod"));
            String selectionCode = stringOrBlank(vyber.get("kod"));
            if (datasetCode.isBlank() || selectionCode.isBlank()) {
                continue;
            }

            String topic = topicForDataset(datasetCode);
            String datasetNameRaw = stringOrBlank(sada.get("nazev"));
            if (datasetNameRaw.isBlank()) {
                datasetNameRaw = datasetCode;
            }
            String selectionName = stringOrBlank(vyber.get("nazev"));
            if (selectionName.isBlank()) {
                selectionName = selectionCode;
            }
            final String datasetName = datasetNameRaw;

            String periodLevels = formatLevels(vyber.get("urovneTypObdobi"));
            String territoryLevels = formatLevels(vyber.get("urovneTypUzemi"));
            String fullPath = topic + " > " + datasetName + " > " + selectionName;

            Map<String, Object> leaf = new LinkedHashMap<>();
            leaf.put("id", selectionCode);
            leaf.put("set_id", selectionCode);
            leaf.put("name", selectionName);
            leaf.put("full_path", fullPath);
            leaf.put("kind", "selection");
            leaf.put("dataset_code", datasetCode);
            leaf.put("dataset_name", datasetName);
            leaf.put("period", periodLevels);
            leaf.put("territory", territoryLevels);
            leaf.put("last_update", stringOrBlank(vyber.get("casZmenyDefinice")));

            selectionIndex.put(selectionCode, leaf);
            grouped.computeIfAbsent(topic, ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(datasetCode, ignored -> new DatasetBucket(datasetCode, datasetName))
                    .sets()
                    .add(leaf);
        }

        List<Map<String, Object>> categories = new ArrayList<>();
        for (Map.Entry<String, Map<String, DatasetBucket>> topicEntry : grouped.entrySet()) {
            String topicLabel = topicEntry.getKey();
            List<Map<String, Object>> datasetNodes = new ArrayList<>();
            for (DatasetBucket dataset : topicEntry.getValue().values()) {
                dataset.sets().sort(Comparator.comparing(item -> lower(item.get("name"))));
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("name", dataset.name() + " (" + dataset.code() + ")");
                node.put("path", topicLabel + " > " + dataset.name());
                node.put("children", List.of());
                node.put("sets", dataset.sets());
                datasetNodes.add(node);
            }
            datasetNodes.sort(Comparator.comparing(item -> lower(item.get("name"))));

            Map<String, Object> categoryNode = new LinkedHashMap<>();
            categoryNode.put("name", topicLabel);
            categoryNode.put("path", topicLabel);
            categoryNode.put("children", datasetNodes);
            categoryNode.put("sets", List.of());
            categories.add(categoryNode);
        }

        categories.sort((a, b) -> {
            String left = stringOrBlank(a.get("name"));
            String right = stringOrBlank(b.get("name"));
            boolean leftOther = "Ostatní".equalsIgnoreCase(left);
            boolean rightOther = "Ostatní".equalsIgnoreCase(right);
            if (leftOther != rightOther) {
                return leftOther ? 1 : -1;
            }
            return left.toLowerCase(Locale.ROOT).compareTo(right.toLowerCase(Locale.ROOT));
        });

        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("categories", categories);
        tree.put("total_sets", selectionIndex.size());
        return new BuildResult(tree, selectionIndex);
    }

    private Map<String, Object> envelope(Map<String, Object> tree, boolean cached, boolean staleFlag) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.putAll(tree);
        out.put("fetched_at", fetchedAtMs / 1000.0);
        out.put("cached", cached);
        out.put("source", "csu");
        if (staleFlag) {
            out.put("stale", true);
            out.put("status", "degraded");
        }
        return out;
    }

    private boolean isStaleFetchedAt() {
        cacheLock.lock();
        try {
            return fetchedAtMs > 0 && (System.currentTimeMillis() - fetchedAtMs) > CACHE_TTL_MS;
        } finally {
            cacheLock.unlock();
        }
    }

    private static String sanitizeSelectionCode(Object raw) {
        String code = stringOrBlank(raw);
        if (code.isBlank()) {
            return "";
        }
        for (int i = 0; i < code.length(); i++) {
            char ch = code.charAt(i);
            if (!(Character.isLetterOrDigit(ch) || ch == '_' || ch == '-')) {
                return "";
            }
        }
        return code;
    }

    private static String formatLevels(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Object nameRaw = map.get("nazevUrovne");
            String name = nameRaw != null ? String.valueOf(nameRaw).trim() : "";
            if (!name.isBlank()) {
                names.add(name);
            }
        }
        return String.join(", ", names);
    }

    private static Map<String, Object> mapField(Map<String, Object> container, String key) {
        Object raw = container.get(key);
        if (raw instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) map;
            return cast;
        }
        return Map.of();
    }

    private static String topicForDataset(String datasetCode) {
        String code = datasetCode != null ? datasetCode.trim().toUpperCase(Locale.ROOT) : "";
        for (PrefixGroup entry : PREFIX_GROUPS) {
            if (code.startsWith(entry.prefix())) {
                return entry.label();
            }
        }
        return "Ostatní";
    }

    private static String lower(Object value) {
        return stringOrBlank(value).toLowerCase(Locale.ROOT);
    }

    private static String stringOrBlank(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
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

    private static double envDouble(String key, double fallback) {
        String raw = BankIntelEnvVars.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private record PrefixGroup(String prefix, String label) {}

    private record DatasetBucket(String code, String name, List<Map<String, Object>> sets) {
        DatasetBucket(String code, String name) {
            this(code, name, new ArrayList<>());
        }
    }

    private record BuildResult(Map<String, Object> tree, Map<String, Map<String, Object>> selectionIndex) {}
}
