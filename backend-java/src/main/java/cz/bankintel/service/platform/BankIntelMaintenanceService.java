package cz.bankintel.service.platform;

import cz.bankintel.search.ClassicCatalogFtsIndexBuilder;
import cz.bankintel.util.BankIntelEnvVars;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Optional shell-out to Python maintenance scripts in {@code BANKINTEL_PYTHON_ROOT}.
 *
 * <p>Disabled unless {@code BANKINTEL_MAINTENANCE_ENABLED=1}.
 *
 * <p>Build classic FTS indexu se sem už neshelluje — přenesl se do Javy
 * ({@link ClassicCatalogFtsIndexBuilder}), takže na produkčním hostu kvůli němu nemusí být Python
 * ani referenční repo. Mirror importy (EBA, EIOPA) zůstávají zatím v Pythonu.
 */
@Service
public class BankIntelMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(BankIntelMaintenanceService.class);

    // build_classic_catalog_fts_index.py tu schválně NENÍ — dělá to ClassicCatalogFtsIndexBuilder.
    private static final List<String> NIGHTLY_SCRIPTS = List.of(
            "scripts/import_eba_public_data.py",
            "scripts/import_eiopa_insurance_statistics.py");

    private final ClassicCatalogFtsIndexBuilder ftsIndexBuilder;

    public BankIntelMaintenanceService(ClassicCatalogFtsIndexBuilder ftsIndexBuilder) {
        this.ftsIndexBuilder = ftsIndexBuilder;
    }

    public boolean maintenanceEnabled() {
        return BankIntelEnvVars.isTruthy("BANKINTEL_MAINTENANCE_ENABLED");
    }

    public boolean pythonAvailable() {
        return resolvePythonRoot() != null;
    }

    public Map<String, Object> runMacroSnapshotRebuild() {
        Map<String, Object> out = new LinkedHashMap<>();
        Path pythonRoot = resolvePythonRoot();
        if (pythonRoot == null) {
            out.put("ok", false);
            out.put("skipped", true);
            out.put("reason", "BANKINTEL_PYTHON_ROOT not configured or missing");
            return out;
        }
        Map<String, Object> result = runPythonScript(pythonRoot, "scripts/build_macro_topics_snapshot.py");
        out.putAll(result);
        return out;
    }

    public Map<String, Object> runNightlyMaintenance() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!maintenanceEnabled()) {
            out.put("ok", false);
            out.put("skipped", true);
            out.put("reason", "BANKINTEL_MAINTENANCE_ENABLED is not set");
            return out;
        }
        List<Map<String, Object>> results = new ArrayList<>();
        boolean allOk = true;

        Map<String, Object> ftsResult = runClassicFtsIndexBuild();
        results.add(ftsResult);
        if (!Boolean.TRUE.equals(ftsResult.get("ok"))) {
            allOk = false;
        }

        // Zbylé noční úlohy jsou pořád Python. Když repo na hostu není, není to důvod celou údržbu
        // shodit — build indexu, tedy to podstatné pro hledání, už proběhl v Javě.
        Path pythonRoot = resolvePythonRoot();
        if (pythonRoot == null) {
            Map<String, Object> skipped = new LinkedHashMap<>();
            skipped.put("ok", false);
            skipped.put("skipped", true);
            skipped.put("scripts", NIGHTLY_SCRIPTS);
            skipped.put("reason", "BANKINTEL_PYTHON_ROOT not configured or missing");
            results.add(skipped);
            allOk = false;
        } else {
            for (String script : NIGHTLY_SCRIPTS) {
                Map<String, Object> result = runPythonScript(pythonRoot, script);
                results.add(result);
                if (!Boolean.TRUE.equals(result.get("ok"))) {
                    allOk = false;
                }
            }
            out.put("python_root", pythonRoot.toString());
        }

        out.put("ok", allOk);
        out.put("results", results);
        return out;
    }

    /**
     * Build classic FTS indexu — dřív shell-out na {@code build_classic_catalog_fts_index.py}.
     *
     * <p>Výjimku nepouští dál: build umí legitimně odmítnout běh (pojistka proti zahození
     * kurátorování v {@link ClassicCatalogFtsIndexBuilder}) a to nemá shodit zbytek noční údržby.
     * Důvod se propíše do výsledku, ať je vidět v logu i v {@code /health}.
     */
    private Map<String, Object> runClassicFtsIndexBuild() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("task", "classic_fts_index_build");
        long started = System.currentTimeMillis();
        try {
            Map<String, Object> summary = ftsIndexBuilder.build();
            out.put("ok", true);
            out.put("total_rows", summary.get("total_rows"));
            out.put("sources", summary.get("sources"));
        } catch (Exception ex) {
            log.warn("classic FTS index build failed: {}", ex.getMessage());
            out.put("ok", false);
            out.put("error", ex.getMessage());
        }
        out.put("elapsed_ms", System.currentTimeMillis() - started);
        return out;
    }

    public Map<String, Object> runPythonScript(Path pythonRoot, String relativeScript) {
        Path scriptPath = pythonRoot.resolve(relativeScript.replace("/", pythonRoot.getFileSystem().getSeparator()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("script", relativeScript);
        if (!Files.isRegularFile(scriptPath)) {
            out.put("ok", false);
            out.put("error", "Script not found: " + scriptPath);
            return out;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("python", scriptPath.toString());
            pb.directory(pythonRoot.toFile());
            pb.environment().putIfAbsent("BANKINTEL_DATA_DIR", BankIntelEnvVars.get("BANKINTEL_DATA_DIR"));
            pb.environment().putIfAbsent("CATALOG_SEARCH_INDEX_DIR", BankIntelEnvVars.get("CATALOG_SEARCH_INDEX_DIR"));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            boolean finished = process.waitFor(30, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                out.put("ok", false);
                out.put("error", "Timeout after 30 minutes");
                return out;
            }
            out.put("exit_code", process.exitValue());
            out.put("ok", process.exitValue() == 0);
            out.put("output_tail", tail(output.toString(), 4000));
            if (process.exitValue() != 0) {
                log.warn("Maintenance script failed {}: {}", relativeScript, tail(output.toString(), 500));
            }
        } catch (Exception ex) {
            out.put("ok", false);
            out.put("error", ex.getMessage());
            log.warn("Maintenance script error {}: {}", relativeScript, ex.getMessage());
        }
        return out;
    }

    private static Path resolvePythonRoot() {
        String raw = BankIntelEnvVars.get("BANKINTEL_PYTHON_ROOT");
        if (raw.isBlank()) {
            return null;
        }
        Path root = Path.of(raw).resolve("backend");
        if (!Files.isDirectory(root)) {
            root = Path.of(raw);
        }
        return Files.isDirectory(root) ? root.toAbsolutePath().normalize() : null;
    }

    private static String tail(String text, int max) {
        if (text == null || text.length() <= max) {
            return text == null ? "" : text;
        }
        return text.substring(text.length() - max);
    }
}
