package cz.bankintel.search;

import cz.bankintel.util.BankIntelEnvVars;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Optional first-boot bootstrap for the 9.5 GB FTS SQLite index from a pre-built snapshot URL.
 *
 * <p>When {@code CLASSIC_CATALOG_FTS_DB} is missing, downloads {@code FTS_INDEX_SNAPSHOT_URL} (HTTP GET),
 * unpacks {@code .zip} / {@code .gz}, and places {@code classic_catalog_search.sqlite} into
 * {@link CatalogSearchProperties#indexDir()}.
 */
@Component
@Order(40)
public class FtsIndexBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FtsIndexBootstrapRunner.class);
    private static final String FTS_DB_NAME = "classic_catalog_search.sqlite";

    private final CatalogSearchProperties properties;
    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofMinutes(2)).followRedirects(HttpClient.Redirect.NORMAL).build();

    public FtsIndexBootstrapRunner(CatalogSearchProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        Path ftsPath = properties.ftsDbPath();
        if (Files.isRegularFile(ftsPath)) {
            log.info(
                    "FTS index bootstrap skipped — {} already exists ({} bytes)",
                    ftsPath.toAbsolutePath().normalize(),
                    safeSize(ftsPath));
            return;
        }

        String snapshotUrl = BankIntelEnvVars.get("FTS_INDEX_SNAPSHOT_URL");
        if (snapshotUrl.isBlank()) {
            log.warn(
                    "FTS index bootstrap skipped — {} missing and FTS_INDEX_SNAPSHOT_URL is not set; "
                            + "catalog search will be slow or empty until index is provisioned (see docs/DEPLOY_DATA.md)",
                    ftsPath.toAbsolutePath().normalize());
            return;
        }

        log.info("FTS index bootstrap starting — downloading snapshot from {}", snapshotUrl);
        Path tempDownload = null;
        try {
            tempDownload = Files.createTempFile("fts-index-snapshot-", guessSuffix(snapshotUrl));
            downloadSnapshot(snapshotUrl, tempDownload);
            Path extracted = extractSqlite(tempDownload, snapshotUrl);
            Path targetDir = properties.indexDir();
            Files.createDirectories(targetDir);
            Path target = targetDir.resolve(FTS_DB_NAME);
            Files.move(extracted, target, StandardCopyOption.REPLACE_EXISTING);
            log.info(
                    "FTS index bootstrap SUCCEEDED — installed {} ({} bytes) from {}",
                    target.toAbsolutePath().normalize(),
                    safeSize(target),
                    snapshotUrl);
        } catch (Exception ex) {
            log.error(
                    "FTS index bootstrap FAILED — could not provision {} from {}: {}",
                    ftsPath.toAbsolutePath().normalize(),
                    snapshotUrl,
                    ex.getMessage(),
                    ex);
        } finally {
            if (tempDownload != null) {
                try {
                    Files.deleteIfExists(tempDownload);
                } catch (IOException ignored) {
                    // best-effort temp cleanup
                }
            }
        }
    }

    private void downloadSnapshot(String url, Path destination) throws IOException, InterruptedException {
        HttpRequest request =
                HttpRequest.newBuilder().uri(URI.create(url)).GET().timeout(Duration.ofHours(3)).build();
        HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(destination));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + " from " + url);
        }
        log.info("FTS snapshot download complete — {} bytes saved to {}", Files.size(destination), destination);
    }

    private Path extractSqlite(Path downloaded, String url) throws IOException {
        String lower = url.toLowerCase(Locale.ROOT);
        String fileName = downloaded.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".zip") || fileName.endsWith(".zip")) {
            return extractFromZip(downloaded);
        }
        if (lower.endsWith(".gz") || fileName.endsWith(".gz")) {
            return gunzipToTemp(downloaded);
        }
        if (downloaded.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(FTS_DB_NAME)) {
            return downloaded;
        }
        Path sqlite = downloaded.resolveSibling(FTS_DB_NAME);
        Files.move(downloaded, sqlite, StandardCopyOption.REPLACE_EXISTING);
        return sqlite;
    }

    private Path extractFromZip(Path zipPath) throws IOException {
        Path tempDir = Files.createTempDirectory("fts-index-unzip-");
        Path found = null;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = Path.of(entry.getName()).getFileName().toString();
                if (!FTS_DB_NAME.equals(name)) {
                    continue;
                }
                Path out = tempDir.resolve(FTS_DB_NAME);
                try (OutputStream os = Files.newOutputStream(out)) {
                    zip.transferTo(os);
                }
                found = out;
                break;
            }
        }
        if (found == null) {
            throw new IOException(FTS_DB_NAME + " not found inside zip archive");
        }
        return found;
    }

    private Path gunzipToTemp(Path gzPath) throws IOException {
        Path out = Files.createTempFile("fts-index-", ".sqlite");
        try (InputStream in = new GZIPInputStream(Files.newInputStream(gzPath));
                OutputStream os = Files.newOutputStream(out)) {
            in.transferTo(os);
        }
        return out;
    }

    private static String guessSuffix(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".zip")) {
            return ".zip";
        }
        if (lower.endsWith(".gz")) {
            return ".gz";
        }
        return ".bin";
    }

    private static long safeSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ex) {
            return -1L;
        }
    }
}
