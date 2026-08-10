package cz.bankintel.service.upload;

import cz.bankintel.config.BankIntelProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class UserPrivateStorageService {

    private final BankIntelProperties properties;

    public Path store(String userId, String uploadId, String ext, byte[] raw) {
        String rel = UploadPolicy.relPath(userId, uploadId, ext);
        Path target = resolveAbsolute(userId, uploadId, ext);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, raw);
        } catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Soubor se nepodařilo uložit: " + e.getMessage());
        }
        return target;
    }

    public byte[] read(String userId, String storedRelPath) {
        Path path = resolveStoredPath(userId, storedRelPath);
        if (!Files.isRegularFile(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Soubor není dostupný.");
        }
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Soubor se nepodařilo načíst.");
        }
    }

    public void delete(String userId, String storedRelPath) {
        try {
            Files.deleteIfExists(resolveStoredPath(userId, storedRelPath));
        } catch (IOException ignored) {
            // best effort
        }
    }

    public Path resolveStoredPath(String userId, String storedRelPath) {
        String rel = storedRelPath == null ? "" : storedRelPath.strip();
        if (rel.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Soubor není dostupný.");
        }
        if (rel.contains("..") || rel.startsWith("/") || rel.contains(":")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Neplatná cesta k souboru.");
        }
        String prefix = "user_private/" + userId + "/";
        if (!rel.startsWith(prefix)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Přístup k souboru odepřen.");
        }
        return baseDir().resolve(rel.replace('/', java.io.File.separatorChar));
    }

    private Path resolveAbsolute(String userId, String uploadId, String ext) {
        String normalized = ext.startsWith(".") ? ext : "." + ext;
        return baseDir().resolve("user_private").resolve(userId).resolve(uploadId + normalized);
    }

    private Path baseDir() {
        String configured = properties.storage().userPrivateDir();
        if (configured == null || configured.isBlank()) {
            return Path.of(System.getProperty("java.io.tmpdir"), "bankintel-user-private");
        }
        return Path.of(configured.strip());
    }
}
