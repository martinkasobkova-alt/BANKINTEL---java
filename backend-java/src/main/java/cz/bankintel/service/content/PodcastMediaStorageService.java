package cz.bankintel.service.content;

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
public class PodcastMediaStorageService {

    private final BankIntelProperties properties;

    public String storeAudio(String episodeId, String ext, byte[] raw) {
        return store(episodeId, "audio" + normalizeExt(ext), raw);
    }

    public String storeCover(String episodeId, String ext, byte[] raw) {
        return store(episodeId, "cover" + normalizeExt(ext), raw);
    }

    public byte[] read(String rel) {
        try {
            return Files.readAllBytes(resolve(rel));
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Soubor není dostupný.");
        }
    }

    public void deleteEpisodeFiles(String audioRel, String coverRel) {
        deleteIfPresent(audioRel);
        deleteIfPresent(coverRel);
    }

    private String store(String episodeId, String filename, byte[] raw) {
        String rel = episodeId + "/" + filename;
        Path target = resolve(rel);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, raw);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Soubor se nepodařilo uložit.");
        }
        return rel;
    }

    private Path resolve(String rel) {
        if (rel == null || rel.isBlank() || rel.contains("..")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Neplatná cesta k souboru.");
        }
        return baseDir().resolve(rel.replace('/', java.io.File.separatorChar));
    }

    private void deleteIfPresent(String rel) {
        if (rel == null || rel.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(resolve(rel));
        } catch (Exception ignored) {
            // best effort
        }
    }

    private Path baseDir() {
        String configured = properties.storage() != null ? properties.storage().podcastMediaDir() : null;
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.strip());
        }
        return Path.of(System.getProperty("java.io.tmpdir"), "bankintel-podcast-media");
    }

    private static String normalizeExt(String ext) {
        if (ext == null || ext.isBlank()) {
            return ".mp3";
        }
        return ext.startsWith(".") ? ext.toLowerCase() : "." + ext.toLowerCase();
    }
}
