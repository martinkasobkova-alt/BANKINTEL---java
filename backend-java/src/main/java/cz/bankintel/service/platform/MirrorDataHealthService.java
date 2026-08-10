package cz.bankintel.service.platform;

import cz.bankintel.search.CatalogIndexStore;
import cz.bankintel.search.CatalogSearchProperties;
import cz.bankintel.util.BankIntelDataPaths;
import cz.bankintel.util.BankIntelEnvVars;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Reports readiness of mirror CSV domains and FTS index — ref audit deploy/mirror gaps. */
@Service
@RequiredArgsConstructor
public class MirrorDataHealthService {

    private static final List<MirrorSpec> MIRRORS = List.of(
            new MirrorSpec("eba", "eba", "eba_banking_long.csv", "import_eba_public_data.py"),
            new MirrorSpec("eiopa", "eiopa/insurance_statistics", "eiopa_insurance_long.csv", "import_eiopa_insurance_statistics.py"),
            new MirrorSpec("entsoe", "entsoe", "entsoe_energy_long.csv", "import_entsoe_energy_data.py"),
            new MirrorSpec("gie", "gie", "gie_energy_long.csv", "import_gie_energy_data.py"));

    private final CatalogIndexStore catalogIndexStore;
    private final CatalogSearchProperties catalogSearchProperties;

    public Map<String, Object> buildHealthReport() {
        Path dataRoot = BankIntelDataPaths.dataDir();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("data_dir", dataRoot.toAbsolutePath().normalize().toString());
        out.put("fts_db_available", catalogIndexStore.ftsDbAvailable());
        out.put("fts_db_path", catalogIndexStore.ftsDbPathLabel());
        out.put("catalog_search_index_dir", BankIntelEnvVars.get("CATALOG_SEARCH_INDEX_DIR"));
        out.put("python_reference_root", BankIntelEnvVars.get("BANKINTEL_PYTHON_ROOT"));

        Map<String, Object> sidecar = buildMetadataSidecarStatus();
        out.put("metadata_sidecar_dir", sidecar);
        out.put("sidecar_ready", sidecar.get("ready"));

        List<Map<String, Object>> mirrors = new ArrayList<>();
        int available = 0;
        for (MirrorSpec spec : MIRRORS) {
            Path csv = dataRoot.resolve(spec.subdir()).resolve(spec.filename());
            boolean ok = Files.isRegularFile(csv);
            if (ok) {
                available++;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("domain", spec.domain());
            row.put("path", csv.toAbsolutePath().normalize().toString());
            row.put("available", ok);
            row.put("import_script", spec.importScript());
            row.put(
                    "hint_cs",
                    ok
                            ? "Mirror CSV je k dispozici."
                            : "Spusťte Python skript "
                                    + spec.importScript()
                                    + " proti BANKINTEL_DATA_DIR (viz docs/DEPLOY_DATA.md).");
            mirrors.add(row);
        }
        out.put("mirrors", mirrors);
        out.put("mirrors_available", available);
        out.put("mirrors_total", MIRRORS.size());
        out.put(
                "overall_ready",
                catalogIndexStore.ftsDbAvailable() && available == MIRRORS.size());
        return out;
    }

    private Map<String, Object> buildMetadataSidecarStatus() {
        Path dir = catalogSearchProperties.metadataDir();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("path", dir.toAbsolutePath().normalize().toString());
        boolean exists = Files.isDirectory(dir);
        status.put("exists", exists);
        int fileCount = 0;
        if (exists) {
            try (Stream<Path> stream = Files.list(dir)) {
                fileCount = (int) stream
                        .filter(p -> Files.isRegularFile(p)
                                && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jsonl"))
                        .count();
            } catch (IOException ex) {
                status.put("scan_error", ex.getMessage());
            }
        }
        status.put("file_count", fileCount);
        status.put("ready", exists && fileCount > 0);
        return status;
    }

    private record MirrorSpec(String domain, String subdir, String filename, String importScript) {}
}
