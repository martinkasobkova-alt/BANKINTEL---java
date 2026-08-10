package cz.bankintel.sources.arad;
import cz.bankintel.util.BankIntelDataPaths;
import cz.bankintel.util.BankIntelEnvVars;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.config.BankIntelProperties;
import cz.bankintel.connector.ConnectorHttpSupport;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
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
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Port of {@code backend/routes/arad_catalog_routes.py} — browse tree from ČNB ARAD v13 sets API
 * with bootstrap fallback from disk.
 */
@Service
public class AradCatalogService {

    private static final Logger log = LoggerFactory.getLogger(AradCatalogService.class);
    private static final URI CATALOG_URL = URI.create("https://www.cnb.cz/aradb/api/v13/sets?lang=cs");
    private static final Pattern SET_ID_SUFFIX = Pattern.compile("\\s*\\((\\d+)\\)\\s*$");
    private static final long CACHE_TTL_MS = Duration.ofHours(6).toMillis();
    private static final double DEFAULT_LIVE_CHECK_TIMEOUT_SEC = 25.0;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Path bootstrapPath;

    private final ReentrantLock cacheLock = new ReentrantLock();
    private Map<String, Object> cachedTree;
    private List<Map<String, Object>> cachedItems = List.of();
    private long fetchedAtMs;
    private String catalogRescue;
    private boolean stale;
    private String warning;

    public AradCatalogService(BankIntelProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        String configured = BankIntelEnvVars.get("ARAD_CATALOG_BOOTSTRAP_PATH");
        if (configured != null && !configured.isBlank()) {
            this.bootstrapPath = Path.of(configured);
        } else {
            this.bootstrapPath = BankIntelDataPaths.resolveDataFile("arad_v13_sets_bootstrap.json");
        }
    }

    @PostConstruct
    void logStartup() {
        log.info(
                "ARAD catalog bootstrap path={} exists={}",
                bootstrapPath.toAbsolutePath().normalize(),
                Files.isRegularFile(bootstrapPath));
    }

    public Map<String, Object> getCatalogEnvelope() {
        cacheLock.lock();
        try {
            if (cachedTree != null && (System.currentTimeMillis() - fetchedAtMs) < CACHE_TTL_MS) {
                return envelope(cachedTree, cachedItems, true, catalogRescue, stale, warning);
            }
        } finally {
            cacheLock.unlock();
        }

        try {
            List<Map<String, Object>> liveItems = fetchLiveItems();
            if (!liveItems.isEmpty()) {
                applyItems(liveItems, System.currentTimeMillis(), null, false, null);
                return envelope(cachedTree, cachedItems, false, null, false, null);
            }
        } catch (Exception ex) {
            log.warn("ARAD live catalog fetch failed: {}", ex.getMessage());
        }

        List<Map<String, Object>> boot = loadBootstrapItems();
        if (!boot.isEmpty()) {
            applyItems(
                    boot,
                    System.currentTimeMillis(),
                    "bootstrap_file",
                    true,
                    "ARAD běží v omezeném bootstrap režimu, výsledky nemusí být kompletní.");
            return envelope(cachedTree, cachedItems, true, catalogRescue, stale, warning);
        }

        Map<String, Object> empty = Map.of("categories", List.of(), "total_sets", 0);
        return envelope(empty, List.of(), false, null, true, "Katalog ARAD se teď nepodařilo načíst.");
    }

    public Map<String, Object> refreshCatalog() {
        try {
            List<Map<String, Object>> liveItems = fetchLiveItems();
            applyItems(liveItems, System.currentTimeMillis(), null, false, null);
            return envelope(cachedTree, cachedItems, false, null, false, null);
        } catch (Exception ex) {
            log.error("ARAD catalog refresh failed: {}", ex.getMessage());
            return Map.of(
                    "ok",
                    false,
                    "detail",
                    "Katalog ARAD není nyní dostupný. Zkuste to prosím později.");
        }
    }

    /**
     * Port of {@code arad_live_check} (backend/routes/arad_catalog_routes.py, ř. 532): admin ping
     * proti živému ARAD API bez cache — latence, HTTP status a velikost odpovědi.
     */
    public Map<String, Object> liveCheck() {
        double timeoutSec = liveCheckTimeoutSeconds();
        long startNanos = System.nanoTime();
        Integer status = null;
        long bytesRead = 0;
        String error = null;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(CATALOG_URL)
                    .timeout(Duration.ofMillis(Math.round(timeoutSec * 1000)))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            status = response.statusCode();
            bytesRead = response.body() != null ? response.body().length : 0;
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP " + status);
            }
        } catch (Exception ex) {
            error = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", error == null && status != null && status >= 200 && status < 300);
        out.put("source", "arad");
        out.put("endpoint", CATALOG_URL.toString());
        out.put("status", status);
        out.put("elapsed_ms", elapsedMs);
        out.put("bytes_read", bytesRead);
        out.put("error", error);
        out.put("timeout", timeoutSec);
        out.put("user_agent", "java-net-http-client-default");
        return out;
    }

    private static double liveCheckTimeoutSeconds() {
        String raw = BankIntelEnvVars.get("ARAD_CATALOG_HTTP_TIMEOUT_SEC");
        if (raw == null || raw.isBlank()) {
            return DEFAULT_LIVE_CHECK_TIMEOUT_SEC;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ex) {
            return DEFAULT_LIVE_CHECK_TIMEOUT_SEC;
        }
    }

    private List<Map<String, Object>> fetchLiveItems() throws IOException, InterruptedException {
        HttpRequest request =
                HttpRequest.newBuilder().uri(CATALOG_URL).timeout(Duration.ofSeconds(25)).GET().build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode());
        }
        String contentType = response.headers().firstValue("content-type").orElse("");
        String bodyText = ConnectorHttpSupport.decodeResponseBody(contentType, response.body());
        Map<String, Object> payload =
                objectMapper.readValue(bodyText, new TypeReference<>() {});
        Object data = payload.get("data");
        if (!(data instanceof List<?> list)) {
            throw new IOException("unexpected JSON shape");
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

    private List<Map<String, Object>> loadBootstrapItems() {
        if (!Files.isRegularFile(bootstrapPath)) {
            log.debug("ARAD bootstrap not found: {}", bootstrapPath);
            return List.of();
        }
        try {
            String json = Files.readString(bootstrapPath);
            Object raw = objectMapper.readValue(json, Object.class);
            if (raw instanceof Map<?, ?> map) {
                Object data = map.get("data");
                if (data instanceof List<?> list) {
                    return castItemList(list);
                }
            }
            if (raw instanceof List<?> list) {
                return castItemList(list);
            }
        } catch (Exception ex) {
            log.warn("ARAD bootstrap unreadable ({}): {}", bootstrapPath, ex.getMessage());
        }
        return List.of();
    }

    private static List<Map<String, Object>> castItemList(List<?> list) {
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

    private void applyItems(
            List<Map<String, Object>> items,
            long fetchedMs,
            String rescue,
            boolean staleFlag,
            String warn) {
        Map<String, Object> tree = buildTree(items);
        cacheLock.lock();
        try {
            cachedTree = tree;
            cachedItems = List.copyOf(items);
            fetchedAtMs = fetchedMs;
            catalogRescue = rescue;
            stale = staleFlag;
            warning = warn;
        } finally {
            cacheLock.unlock();
        }
    }

    static Map<String, Object> buildTree(List<Map<String, Object>> items) {
        TreeNode root = new TreeNode("", "");
        for (Map<String, Object> it : items) {
            Object nameObj = it.get("name");
            String fullCs;
            if (nameObj instanceof Map<?, ?> nameMap) {
                Object cs = nameMap.get("cs");
                fullCs = cs != null ? String.valueOf(cs) : "";
            } else {
                fullCs = nameObj != null ? String.valueOf(nameObj) : "";
            }
            if (fullCs.isBlank()) {
                continue;
            }
            String[] parts = fullCs.split(">");
            List<String> trimmed = new ArrayList<>();
            for (String p : parts) {
                String t = p.trim();
                if (!t.isEmpty()) {
                    trimmed.add(t);
                }
            }
            if (trimmed.isEmpty()) {
                continue;
            }
            String leafLabel = stripSetIdSuffix(trimmed.get(trimmed.size() - 1));
            TreeNode node = root;
            List<String> breadcrumbs = new ArrayList<>();
            for (int i = 0; i < trimmed.size() - 1; i++) {
                String cat = trimmed.get(i);
                breadcrumbs.add(cat);
                String key = String.join(" > ", breadcrumbs);
                node = node.child(key, cat, key);
            }
            Map<String, Object> set = new LinkedHashMap<>();
            set.put("id", it.get("id"));
            set.put("set_id", String.valueOf(it.get("id") != null ? it.get("id") : ""));
            set.put("name", leafLabel.isBlank() ? fullCs : leafLabel);
            set.put("full_path", fullCs);
            node.sets.add(set);
        }
        Map<String, Object> finalized = root.finalizeNode();
        return Map.of(
                "categories",
                finalized.get("children"),
                "total_sets",
                items.size());
    }

    private static String stripSetIdSuffix(String label) {
        Matcher m = SET_ID_SUFFIX.matcher(label);
        if (m.find()) {
            return label.substring(0, m.start()).trim();
        }
        return label.trim();
    }

    private Map<String, Object> envelope(
            Map<String, Object> tree,
            List<Map<String, Object>> items,
            boolean cached,
            String rescue,
            boolean staleFlag,
            String warn) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.putAll(tree);
        out.put("items", items != null ? items : List.of());
        out.put("fetched_at", fetchedAtMs / 1000.0);
        out.put("cached", cached);
        out.put("source", "arad");
        out.put("stale", staleFlag);
        out.put("catalog_rescue", rescue);
        out.put("warning", warn);
        out.put("refresh_in_progress", false);
        if (staleFlag) {
            out.put("status", "degraded");
        }
        return out;
    }

    private static final class TreeNode {
        final String name;
        final String path;
        final Map<String, TreeNode> childrenMap = new LinkedHashMap<>();
        final List<Map<String, Object>> sets = new ArrayList<>();

        TreeNode(String name, String path) {
            this.name = name;
            this.path = path;
        }

        TreeNode child(String key, String name, String path) {
            return childrenMap.computeIfAbsent(key, k -> new TreeNode(name, path));
        }

        Map<String, Object> finalizeNode() {
            List<Map<String, Object>> children = new ArrayList<>();
            for (TreeNode c : childrenMap.values()) {
                children.add(c.finalizeNode());
            }
            children.sort(Comparator.comparing(m -> String.valueOf(m.get("name")).toLowerCase()));
            List<Map<String, Object>> sortedSets = new ArrayList<>(sets);
            sortedSets.sort(Comparator.comparing(m -> String.valueOf(m.get("name")).toLowerCase()));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", name);
            out.put("path", path);
            out.put("children", children);
            out.put("sets", sortedSets);
            return out;
        }
    }
}
