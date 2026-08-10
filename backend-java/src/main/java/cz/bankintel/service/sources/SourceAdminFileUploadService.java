package cz.bankintel.service.sources;

import cz.bankintel.service.userdata.UserDataParseService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class SourceAdminFileUploadService {

    private static final long MAX_UPLOAD_BYTES = 50L * 1024 * 1024;
    private static final Set<String> ALLOWED_EXT = Set.of(".xlsx", ".xlsm", ".csv", ".pdf");

    private final AdminSourceUploadStorage uploadStorage;
    private final SourceFileMetaService sourceFileMetaService;

    public Map<String, Object> uploadFile(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getOriginalFilename() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing filename");
        }
        String ext = Path.of(file.getOriginalFilename()).getFileName().toString();
        ext = ext.contains(".") ? ext.substring(ext.lastIndexOf('.')).toLowerCase(Locale.ROOT) : "";
        if (!ALLOWED_EXT.contains(ext)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported file type " + ext);
        }
        byte[] raw = readBytes(file);
        if (raw.length > MAX_UPLOAD_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "file too large");
        }
        try {
            String rel = uploadStorage.storeNewFile(ext, raw);
            Map<String, Object> metaResult = sourceFileMetaService.fileMeta(rel, null, 200);
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) metaResult.getOrDefault("meta", Map.of());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", rel);
            out.put("original_name", file.getOriginalFilename());
            out.put("size_bytes", raw.length);
            out.put("meta", meta);
            return out;
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Nahrání souboru selhalo.");
        }
    }

    public Map<String, Object> pdfExtractPreview(String path, Map<String, Object> queryParams) {
        if (path == null || path.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing path");
        }
        try {
            byte[] raw = uploadStorage.read(path.trim());
            UserDataParseService.ParseResult parsed =
                    UserDataParseService.parseUpload(raw, ".pdf", "admin", "admin", "preview", "preview.pdf");
            List<Map<String, Object>> rows = parsed.series().stream()
                    .flatMap(s -> s.getObservations() != null ? s.getObservations().stream() : java.util.stream.Stream.empty())
                    .limit(60)
                    .toList();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("rows", rows);
            out.put("meta", parsed.meta());
            out.put("query_params", queryParams != null ? queryParams : Map.of());
            return out;
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "uploaded file not found");
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nelze zpracovat PDF s těmito parametry.");
        }
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Soubor se nepodařilo načíst.");
        }
    }
}
