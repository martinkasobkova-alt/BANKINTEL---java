package cz.bankintel.connector;

import cz.bankintel.service.sources.AdminSourceUploadStorage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Konektor pro synchronizaci nahraných souborů (xlsx, csv, pdf, json, olap).
 *
 * <p>Python originál: {@code connectors/file_upload.py}. Soubor je v {@code source.endpoint}
 * (relativní cesta v admin uploads).
 */
@Component
@RequiredArgsConstructor
public class FileUploadConnector implements BaseConnector {

    private final AdminSourceUploadStorage uploadStorage;

    @Override
    public String sourceType() {
        return "file_upload";
    }

    @Override
    public ConnectorFetchResult fetch(Map<String, Object> source) {
        String rel = string(source.get("endpoint"));
        Map<String, Object> params =
                source.get("query_params") instanceof Map<?, ?> m ? ConnectorHttpSupport.stringMap(m) : Map.of();
        if (rel.isBlank()) {
            return ConnectorFetchResult.error(
                    400,
                    Map.of("error", "missing uploaded file path in source.endpoint"),
                    source);
        }
        try {
            Path path = uploadStorage.resolve(rel);
            byte[] raw = uploadStorage.read(rel);
            String kind = FileUploadReadSupport.detectKind(path, params);
            List<Map<String, Object>> rows = FileUploadReadSupport.readRows(raw, path, params);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("kind", kind);
            payload.put("file", rel);
            payload.put(
                    "original_name",
                    string(params.get("original_name")).isBlank()
                            ? path.getFileName().toString()
                            : string(params.get("original_name")));
            payload.put("row_count", rows.size());
            payload.put("rows", rows);
            return ConnectorFetchResult.ok(payload, source);
        } catch (Exception ex) {
            String message = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            return ConnectorFetchResult.error(
                    502,
                    Map.of("error", message.length() > 500 ? message.substring(0, 500) : message),
                    source);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> parse(Object raw, Map<String, Object> source) {
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return List.of();
        }
        Object rowsObj = ((Map<String, Object>) rawMap).get("rows");
        if (!(rowsObj instanceof List<?> rows)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object row : rows) {
            if (row instanceof Map<?, ?> map) {
                out.add(new LinkedHashMap<>((Map<String, Object>) map));
            }
        }
        return out;
    }

    private static String string(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }
}
