package cz.bankintel.util;

import java.nio.file.Path;

/** Resolved paths to BankIntel data files. */
public final class BankIntelDataPaths {

    private BankIntelDataPaths() {}

    public static Path dataDir() {
        String configured = BankIntelEnvVars.get("BANKINTEL_DATA_DIR");
        if (configured == null || configured.isBlank()) {
            configured = BankIntelEnvVars.get("COMMODITIES_DATA_DIR");
        }
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return localBackendRoot().resolve("data");
    }

    /** Optional read-only root of the original Python {@code Bankoapp-main} tree. */
    public static Path referenceBackendRoot() {
        String configured = BankIntelEnvVars.get("BANKINTEL_REFERENCE_ROOT");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        Path projectRoot = localProjectRoot();
        Path sibling = projectRoot.resolveSibling("Bankoapp-main").normalize();
        if (!sibling.equals(projectRoot)) {
            return sibling;
        }
        return projectRoot;
    }

    public static Path catalogSearchMetadataDir() {
        String configured = BankIntelEnvVars.get("CATALOG_SEARCH_METADATA_DIR");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return dataDir().resolve("catalog_search_metadata");
    }

    public static Path catalogSearchIndexDir() {
        String configured = BankIntelEnvVars.get("CATALOG_SEARCH_INDEX_DIR");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return dataDir().resolve("catalog_search_indexes");
    }

    public static Path resolveDataFile(String name) {
        return dataDir().resolve(name);
    }

    public static Path oecd4Dir() {
        return dataDir().resolve("oecd4");
    }

    /** Curated Eurostat/manager segment bundle JSON files (25 files, {@code source: "eurostat"}
     * rows carry a fully-resolved {@code query_params} per series) - lives under the Python
     * tree's {@code backend/config/}, a sibling of {@code backend/data/}, not inside it. */
    public static Path managerSegmentBundlesDir() {
        String configured = BankIntelEnvVars.get("MANAGER_SEGMENT_BUNDLES_DIR");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return referenceBackendRoot().resolve("backend").resolve("config").resolve("manager_segments");
    }

    public static Path macroTopicsSnapshotPath() {
        String configured = BankIntelEnvVars.get("MACRO_TOPICS_SNAPSHOT_PATH");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return dataDir().resolve("macro_topics_snapshot.json");
    }

    public static Path macroTopicsSnapshotPartsDir() {
        String configured = BankIntelEnvVars.get("MACRO_TOPICS_SNAPSHOT_PARTS_DIR");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return dataDir().resolve("macro_topics_snapshot_parts");
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
}
