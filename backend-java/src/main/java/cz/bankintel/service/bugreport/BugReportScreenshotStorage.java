package cz.bankintel.service.bugreport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BugReportScreenshotStorage {

    private static final Logger log = LoggerFactory.getLogger(BugReportScreenshotStorage.class);

    public static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final Set<String> ALLOWED_EXT = Set.of(".png", ".jpg", ".jpeg", ".webp");
    private static final Set<String> ALLOWED_MIME = Set.of("image/png", "image/jpeg", "image/webp");

    @Value("${BUG_REPORT_UPLOADS_DIR:}")
    private String storageDirOverride;

    public Map<String, Object> save(byte[] data, String contentType, String filename) {
        if (data == null || data.length == 0 || data.length > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Screenshot musí být obrázek PNG, JPG nebo WEBP do 5 MB.");
        }
        ImageSniff sniff = sniffImage(data);
        if (sniff == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Screenshot musí být obrázek PNG, JPG nebo WEBP do 5 MB.");
        }
        String extFromName = extension(filename);
        if (extFromName != null && !ALLOWED_EXT.contains(extFromName) && !(".jpeg".equals(extFromName) && ".jpg".equals(sniff.ext()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Screenshot musí být obrázek PNG, JPG nebo WEBP do 5 MB.");
        }
        String mime = normalizeMime(contentType);
        if (mime != null && !ALLOWED_MIME.contains(mime)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Screenshot musí být obrázek PNG, JPG nebo WEBP do 5 MB.");
        }

        Path root = rootDir();
        try {
            Files.createDirectories(root);
            String outName = UUID.randomUUID() + sniff.ext();
            Path target = root.resolve(outName).normalize();
            if (!target.getParent().equals(root)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Screenshot musí být obrázek PNG, JPG nebo WEBP do 5 MB.");
            }
            Files.write(target, data);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("stored_path", "bug_report_uploads/" + outName);
            out.put("original_name", safeOriginalName(filename));
            out.put("content_type", sniff.mime());
            out.put("size", data.length);
            return out;
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Screenshot musí být obrázek PNG, JPG nebo WEBP do 5 MB.");
        }
    }

    /**
     * Port of {@code delete_screenshot_file} (backend/services/bug_report_storage.py): best-effort
     * delete, silently ignores a missing/invalid path instead of throwing.
     */
    public void deleteScreenshotFile(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            return;
        }
        if (storedPath.contains("..") || storedPath.startsWith("/") || storedPath.startsWith("\\")) {
            return;
        }
        if (!storedPath.startsWith("bug_report_uploads/")) {
            return;
        }
        Path root = rootDir();
        Path file = root.resolve(storedPath.substring("bug_report_uploads/".length())).normalize();
        if (!file.getParent().equals(root)) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ex) {
            log.warn("bug screenshot delete failed: {}", ex.getMessage());
        }
    }

    public Path resolveStoredPath(String storedPath) {
        if (storedPath == null || storedPath.contains("..") || storedPath.startsWith("/") || storedPath.startsWith("\\")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Screenshot nenalezen");
        }
        if (!storedPath.startsWith("bug_report_uploads/")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Screenshot nenalezen");
        }
        Path root = rootDir();
        Path file = root.resolve(storedPath.substring("bug_report_uploads/".length())).normalize();
        if (!file.getParent().equals(root) || !Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Screenshot nenalezen");
        }
        return file;
    }

    private Path rootDir() {
        if (storageDirOverride != null && !storageDirOverride.isBlank()) {
            return Path.of(storageDirOverride.trim()).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("java.io.tmpdir"), "bankintel-bug-reports").toAbsolutePath().normalize();
    }

    private static ImageSniff sniffImage(byte[] data) {
        if (data.length < 12) {
            return null;
        }
        if (data[0] == (byte) 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47) {
            return new ImageSniff(".png", "image/png");
        }
        if (data[0] == (byte) 0xFF && data[1] == (byte) 0xD8) {
            return new ImageSniff(".jpg", "image/jpeg");
        }
        if (data[0] == 0x52 && data[1] == 0x49 && data[2] == 0x46 && data[3] == 0x46 && data[8] == 0x57 && data[9] == 0x45 && data[10] == 0x42 && data[11] == 0x50) {
            return new ImageSniff(".webp", "image/webp");
        }
        return null;
    }

    private static String extension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return null;
        }
        String ext = filename.substring(filename.lastIndexOf('.')).toLowerCase(Locale.ROOT);
        return ".jpeg".equals(ext) ? ".jpg" : ext;
    }

    private static String normalizeMime(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        String mime = contentType.split(";")[0].strip().toLowerCase(Locale.ROOT);
        return "image/jpg".equals(mime) ? "image/jpeg" : mime;
    }

    private static String safeOriginalName(String name) {
        String base = Path.of(name != null ? name : "image").getFileName().toString();
        String cleaned = base.replaceAll("[^A-Za-z0-9._-]+", "_");
        return cleaned.isBlank() ? "image" : cleaned.substring(0, Math.min(180, cleaned.length()));
    }

    private record ImageSniff(String ext, String mime) {}
}
