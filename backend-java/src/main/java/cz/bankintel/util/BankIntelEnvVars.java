package cz.bankintel.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads local env files into a lightweight in-process map.
 *
 * <p>Precedence is: OS environment, JVM system properties, then files. By default the
 * loader reads {@code backend-java/dev.local.env}, {@code BankIntel-v2/dev.local.env}, and
 * {@code backend-java/.env} when they exist. The original Python app is never read unless
 * explicitly configured through {@code BANKINTEL_ENV_FILE} or {@code BANKINTEL_DEV_LOCAL_ENV}.
 */
public final class BankIntelEnvVars {

    private static final Logger log = LoggerFactory.getLogger(BankIntelEnvVars.class);
    private static final Map<String, String> FILE_VALUES = new LinkedHashMap<>();
    private static volatile boolean loaded;

    private BankIntelEnvVars() {}

    public static Map<String, Object> loadAsPropertyMap() {
        ensureLoaded();
        Map<String, Object> out = new LinkedHashMap<>();
        FILE_VALUES.forEach(out::put);
        applyDerivedDefaults(out);
        return out;
    }

    public static String get(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String os = System.getenv(name);
        if (os != null && !os.isBlank()) {
            return os.trim();
        }
        String prop = System.getProperty(name);
        if (prop != null && !prop.isBlank()) {
            return prop.trim();
        }
        ensureLoaded();
        String file = FILE_VALUES.get(name);
        return file != null ? file.trim() : "";
    }

    public static boolean isTruthy(String name) {
        String value = get(name).toLowerCase(Locale.ROOT);
        return Set.of("1", "true", "yes", "on").contains(value);
    }

    public static boolean isFalsy(String name) {
        String value = get(name).toLowerCase(Locale.ROOT);
        return Set.of("0", "false", "no", "off").contains(value);
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (BankIntelEnvVars.class) {
            if (loaded) {
                return;
            }
            for (Path path : resolveEnvFiles()) {
                mergeFile(path);
            }
            applyDerivedDefaultsToFileMap();
            loaded = true;
            log.info(
                    "BankIntel env files loaded ({} keys), dev.local.env={}, backend .env={}",
                    FILE_VALUES.size(),
                    resolveDefaultDevLocalEnvPaths().stream().anyMatch(Files::isRegularFile),
                    Files.isRegularFile(defaultBackendEnvPath()));
        }
    }

    private static List<Path> resolveEnvFiles() {
        String custom = firstNonBlank(System.getenv("BANKINTEL_ENV_FILE"), System.getProperty("BANKINTEL_ENV_FILE"));
        if (!custom.isBlank()) {
            return List.of(Path.of(custom).toAbsolutePath().normalize());
        }
        List<Path> devLocal = resolveDefaultDevLocalEnvPaths();
        return List.of(devLocal.get(0), devLocal.get(1), defaultBackendEnvPath());
    }

    private static List<Path> resolveDefaultDevLocalEnvPaths() {
        String configured = firstNonBlank(
                System.getenv("BANKINTEL_DEV_LOCAL_ENV"),
                System.getProperty("BANKINTEL_DEV_LOCAL_ENV"),
                System.getenv("BANKO_DEV_LOCAL_ENV"),
                System.getProperty("BANKO_DEV_LOCAL_ENV"));
        if (!configured.isBlank()) {
            Path path = Path.of(configured).toAbsolutePath().normalize();
            return List.of(path, path);
        }
        Path backend = localBackendRoot();
        Path project = localProjectRoot();
        return List.of(backend.resolve("dev.local.env"), project.resolve("dev.local.env"));
    }

    private static Path defaultBackendEnvPath() {
        return localBackendRoot().resolve(".env");
    }

    private static void mergeFile(Path path) {
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                parseLine(line, FILE_VALUES);
            }
        } catch (IOException ex) {
            log.warn("Cannot read env file {}: {}", path, ex.getMessage());
        }
    }

    private static void parseLine(String line, Map<String, String> target) {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return;
        }
        int eq = trimmed.indexOf('=');
        if (eq < 1) {
            return;
        }
        String key = trimmed.substring(0, eq).trim();
        String value = trimmed.substring(eq + 1).trim();
        if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1);
        }
        if (!key.isEmpty()) {
            target.put(key, value);
        }
    }

    private static void applyDerivedDefaultsToFileMap() {
        Map<String, Object> tmp = new LinkedHashMap<>(FILE_VALUES);
        applyDerivedDefaults(tmp);
        tmp.forEach((key, value) -> {
            String current = FILE_VALUES.getOrDefault(key, "");
            if (current.isBlank()) {
                FILE_VALUES.put(key, String.valueOf(value));
            }
        });
    }

    private static void applyDerivedDefaults(Map<String, Object> target) {
        String bankIntelDataDir = stringValue(target.get("BANKINTEL_DATA_DIR"));
        Path dataDir = bankIntelDataDir.isBlank() ? localBackendRoot().resolve("data") : Path.of(bankIntelDataDir);

        String indexDir = stringValue(target.get("CATALOG_SEARCH_INDEX_DIR"));
        if (indexDir.isBlank()) {
            indexDir = dataDir.resolve("catalog_search_indexes").toString();
            target.put("CATALOG_SEARCH_INDEX_DIR", indexDir);
        }

        String metadataDir = stringValue(target.get("CATALOG_SEARCH_METADATA_DIR"));
        if (metadataDir.isBlank()) {
            target.put("CATALOG_SEARCH_METADATA_DIR", deriveCatalogSearchMetadataDir(dataDir).toString());
        }

        String ftsDb = stringValue(target.get("CLASSIC_CATALOG_FTS_DB"));
        if (ftsDb.isBlank()) {
            target.put("CLASSIC_CATALOG_FTS_DB", Path.of(indexDir).resolve("classic_catalog_search.sqlite").toString());
        }

        if (bankIntelDataDir.isBlank()) {
            target.put("BANKINTEL_DATA_DIR", dataDir.toString());
        }

        String frontend = stringValue(target.get("FRONTEND_URL"));
        if (frontend.isBlank()) {
            target.put("FRONTEND_URL", "http://localhost:5173");
        }
        if (!target.containsKey("OPENAI_MODEL_CHAT") || stringValue(target.get("OPENAI_MODEL_CHAT")).isBlank()) {
            target.put("OPENAI_MODEL_CHAT", "gpt-5.4-mini");
        }
        if (!target.containsKey("OPENAI_MODEL_PLANNER") || stringValue(target.get("OPENAI_MODEL_PLANNER")).isBlank()) {
            target.put("OPENAI_MODEL_PLANNER", "gpt-5.4-nano");
        }
        if (!target.containsKey("SEARCH_V2_LLM_CONNECT_TIMEOUT_MS")
                || stringValue(target.get("SEARCH_V2_LLM_CONNECT_TIMEOUT_MS")).isBlank()) {
            target.put("SEARCH_V2_LLM_CONNECT_TIMEOUT_MS", "3000");
        }
        if (!target.containsKey("SEARCH_V2_LLM_REQUEST_TIMEOUT_MS")
                || stringValue(target.get("SEARCH_V2_LLM_REQUEST_TIMEOUT_MS")).isBlank()) {
            target.put("SEARCH_V2_LLM_REQUEST_TIMEOUT_MS", "12000");
        }
        if (!target.containsKey("SEARCH_V2_LLM_REASONING_EFFORT")
                || stringValue(target.get("SEARCH_V2_LLM_REASONING_EFFORT")).isBlank()) {
            target.put("SEARCH_V2_LLM_REASONING_EFFORT", "none");
        }
    }

    private static Path deriveCatalogSearchMetadataDir(Path dataDir) {
        Path normalizedDataDir = dataDir.toAbsolutePath().normalize();
        Path parent = normalizedDataDir.getParent();
        if (parent != null) {
            Path siblingConfig = parent.resolve("config").resolve("catalog_search_metadata");
            if (Files.isDirectory(siblingConfig)) {
                return siblingConfig;
            }
        }

        Path localConfig = localProjectRoot().resolve("config").resolve("catalog_search_metadata");
        if (Files.isDirectory(localConfig)) {
            return localConfig;
        }

        return normalizedDataDir.resolve("catalog_search_metadata");
    }

    private static Path localBackendRoot() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        if ("backend-java".equalsIgnoreCase(String.valueOf(cwd.getFileName()))) {
            return cwd;
        }
        Path nestedBackend = cwd.resolve("backend-java");
        if (nestedBackend.resolve("src/main").toFile().isDirectory()) {
            return nestedBackend.normalize();
        }
        return cwd;
    }

    private static Path localProjectRoot() {
        Path backend = localBackendRoot();
        if ("backend-java".equalsIgnoreCase(String.valueOf(backend.getFileName())) && backend.getParent() != null) {
            return backend.getParent();
        }
        return backend;
    }

    private static String stringValue(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
