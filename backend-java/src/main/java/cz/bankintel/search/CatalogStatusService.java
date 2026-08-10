package cz.bankintel.search;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CatalogStatusService {

    private final CatalogSearchProperties properties;
    private final CatalogIndexStore indexStore;

    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("browse_ui_timeout_ms", 20_000);
        body.put("browse_http_timeout_ms", 24_000);
        body.put("browse_ui_timeout_ms_arad", 125_000);
        body.put("browse_http_timeout_ms_arad", 120_000);
        body.put("fred_api_key_configured", properties.fredApiKeyConfigured());
        body.put("sources", CatalogSourceRegistry.statusSources());
        body.put("index_dir", properties.indexDir().toString());
        body.put("fts_db", properties.ftsDbPath().toString());
        body.put("fts_db_available", indexStore.ftsDbAvailable());
        body.put("metadata_dir", properties.metadataDir().toString());
        body.put("metadata_sidecar_enabled", true);
        return body;
    }
}
