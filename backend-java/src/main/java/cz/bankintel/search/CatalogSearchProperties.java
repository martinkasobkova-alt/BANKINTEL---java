package cz.bankintel.search;

import cz.bankintel.config.BankIntelProperties;
import cz.bankintel.util.BankIntelDataPaths;
import cz.bankintel.util.BankIntelEnvVars;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
public class CatalogSearchProperties {

    private static final String FTS_DB_NAME = "classic_catalog_search.sqlite";

    private final Path indexDir;
    private final Path ftsDbPath;
    private final Path metadataDir;
    private final Path sidecarDir;
    private final Path sidecarFtsDbPath;

    public CatalogSearchProperties(BankIntelProperties bankIntelProperties) {
        BankIntelProperties.Catalog catalog = bankIntelProperties.catalog();
        String indexDirRaw =
                catalog != null && catalog.indexDir() != null && !catalog.indexDir().isBlank()
                        ? catalog.indexDir()
                        : BankIntelEnvVars.get("CATALOG_SEARCH_INDEX_DIR");
        if (indexDirRaw == null || indexDirRaw.isBlank()) {
            this.indexDir = BankIntelDataPaths.catalogSearchIndexDir().toAbsolutePath().normalize();
        } else {
            this.indexDir = Path.of(indexDirRaw).toAbsolutePath().normalize();
        }

        String metadataDirEnv = BankIntelEnvVars.get("CATALOG_SEARCH_METADATA_DIR");
        String metadataDirRaw = metadataDirEnv != null && !metadataDirEnv.isBlank()
                ? metadataDirEnv
                : catalog != null && catalog.metadataDir() != null && !catalog.metadataDir().isBlank()
                        ? catalog.metadataDir()
                        : "";
        if (metadataDirRaw == null || metadataDirRaw.isBlank()) {
            this.metadataDir = BankIntelDataPaths.catalogSearchMetadataDir().toAbsolutePath().normalize();
        } else {
            this.metadataDir = Path.of(metadataDirRaw).toAbsolutePath().normalize();
        }

        String ftsDbRaw = catalog != null ? catalog.ftsDb() : null;
        if (ftsDbRaw != null && !ftsDbRaw.isBlank()) {
            this.ftsDbPath = Path.of(ftsDbRaw).toAbsolutePath().normalize();
        } else {
            this.ftsDbPath = indexDir.resolve(FTS_DB_NAME);
        }

        String sidecarDirRaw = BankIntelEnvVars.get("SEARCH_CATALOG_SIDECAR_DIR");
        if (sidecarDirRaw == null || sidecarDirRaw.isBlank()) {
            Path base = indexDir.getParent() != null ? indexDir.getParent() : indexDir;
            this.sidecarDir = base.resolve("search_v2_sidecar").toAbsolutePath().normalize();
        } else {
            this.sidecarDir = Path.of(sidecarDirRaw).toAbsolutePath().normalize();
        }

        String sidecarFtsRaw = BankIntelEnvVars.get("SEARCH_CATALOG_SIDECAR_FTS_DB");
        if (sidecarFtsRaw == null || sidecarFtsRaw.isBlank()) {
            this.sidecarFtsDbPath = sidecarDir.resolve("search_v2_sidecar.sqlite");
        } else {
            this.sidecarFtsDbPath = Path.of(sidecarFtsRaw).toAbsolutePath().normalize();
        }
    }

    public Path indexDir() {
        return indexDir;
    }

    public Path ftsDbPath() {
        return ftsDbPath;
    }

    public Path jsonlPath(String source) {
        return indexDir.resolve(source + ".jsonl");
    }

    public Path metaPath(String source) {
        return indexDir.resolve(source + ".meta.json");
    }

    /** Sidecar JSONL from Python {@code backend/config/catalog_search_metadata/}. */
    public Path metadataPath(String source) {
        return metadataDir.resolve(source + ".jsonl");
    }

    public Path metadataDir() {
        return metadataDir;
    }

    public Path sidecarDir() {
        return sidecarDir;
    }

    public Path sidecarJsonlPath(String source) {
        return sidecarDir.resolve(source + ".jsonl");
    }

    public Path sidecarFtsDbPath() {
        return sidecarFtsDbPath;
    }

    public boolean fredApiKeyConfigured() {
        String key = BankIntelEnvVars.get("FRED_API_KEY");
        return key != null && !key.isBlank();
    }
}
