package cz.bankintel.service.sources;

import cz.bankintel.config.BankIntelProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminSourceUploadStorage {

    private final BankIntelProperties properties;

    public Path resolve(String rel) throws IOException {
        if (rel == null || rel.isBlank()) {
            throw new IOException("empty file path");
        }
        Path base = baseDir().toAbsolutePath().normalize();
        Path resolved = base.resolve(rel).normalize();
        if (!resolved.startsWith(base)) {
            throw new IOException("path escapes uploads dir");
        }
        if (!Files.isRegularFile(resolved)) {
            throw new IOException("uploaded file not found");
        }
        return resolved;
    }

    public byte[] read(String rel) throws IOException {
        return Files.readAllBytes(resolve(rel));
    }

    public String storeNewFile(String ext, byte[] raw) throws IOException {
        if (ext == null || ext.isBlank()) {
            throw new IOException("missing extension");
        }
        String normalized = ext.startsWith(".") ? ext.toLowerCase() : "." + ext.toLowerCase();
        String rel = java.util.UUID.randomUUID().toString().replace("-", "") + normalized;
        Path base = baseDir().toAbsolutePath().normalize();
        Path target = base.resolve(rel).normalize();
        if (!target.startsWith(base)) {
            throw new IOException("path escapes uploads dir");
        }
        Files.createDirectories(base);
        Files.write(target, raw);
        return rel;
    }

    private Path baseDir() {
        String configured = properties.storage() != null ? properties.storage().adminUploadsDir() : null;
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.strip());
        }
        return Path.of(System.getProperty("java.io.tmpdir"), "bankintel-uploads");
    }
}
