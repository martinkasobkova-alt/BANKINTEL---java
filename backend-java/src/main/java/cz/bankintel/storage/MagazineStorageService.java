package cz.bankintel.storage;

import cz.bankintel.domain.entity.StoredFileEntity;
import cz.bankintel.repository.StoredFileRepository;
import cz.bankintel.util.IdGenerator;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class MagazineStorageService {

    private final StoredFileRepository storedFileRepository;

    @Value("${MAGAZINE_STORAGE_DIR:}")
    private String storageDirOverride;

    private Path rootDir;

    @PostConstruct
    void init() throws IOException {
        if (storageDirOverride != null && !storageDirOverride.isBlank()) {
            rootDir = Path.of(storageDirOverride.trim()).toAbsolutePath().normalize();
        } else {
            rootDir = Path.of("data", "magazines").toAbsolutePath().normalize();
        }
        Files.createDirectories(rootDir);
    }

    public StoredFileEntity storePdf(String magazineId, String issueId, String originalName, byte[] raw) {
        if (raw == null || raw.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Soubor je prázdný.");
        }
        String safeName = sanitizeFileName(originalName);
        String storageKey = magazineId + "/" + issueId + ".pdf";
        Path target = rootDir.resolve(storageKey).normalize();
        if (!target.startsWith(rootDir)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Neplatná cesta souboru.");
        }
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, raw);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Uložení PDF selhalo.", e);
        }
        StoredFileEntity entity = new StoredFileEntity();
        entity.setId(IdGenerator.newId());
        entity.setStorageKey(storageKey);
        entity.setOriginalName(safeName);
        entity.setContentType("application/pdf");
        entity.setSizeBytes(raw.length);
        entity.setCreatedAt(Instant.now());
        return storedFileRepository.save(entity);
    }

    public byte[] readPdf(StoredFileEntity file) {
        if (file == null || file.getStorageKey() == null || file.getStorageKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PDF soubor není dostupný.");
        }
        Path path = rootDir.resolve(file.getStorageKey()).normalize();
        if (!path.startsWith(rootDir) || !Files.isRegularFile(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PDF soubor není dostupný.");
        }
        try {
            byte[] raw = Files.readAllBytes(path);
            if (raw.length == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PDF soubor je prázdný.");
            }
            return raw;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PDF soubor není dostupný.", e);
        }
    }

    public void deleteFile(StoredFileEntity file) {
        if (file == null || file.getStorageKey() == null) {
            return;
        }
        Path path = rootDir.resolve(file.getStorageKey()).normalize();
        if (path.startsWith(rootDir)) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // best effort
            }
        }
        storedFileRepository.delete(file);
    }

    private static String sanitizeFileName(String name) {
        String raw = name == null ? "issue.pdf" : name.trim();
        if (raw.isEmpty()) {
            return "issue.pdf";
        }
        String base = Path.of(raw).getFileName().toString();
        return base.isEmpty() ? "issue.pdf" : base;
    }
}
