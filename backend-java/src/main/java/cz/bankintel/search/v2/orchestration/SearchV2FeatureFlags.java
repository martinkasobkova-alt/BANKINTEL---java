package cz.bankintel.search.v2.orchestration;

import cz.bankintel.search.model.CatalogMapSupport;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SearchV2FeatureFlags {

    @Value("${bankintel.search.engine-version:${SEARCH_ENGINE_VERSION:v1}}")
    private String configuredVersion;

    @Value("${bankintel.search.shadow-mode:${SEARCH_V2_SHADOW_MODE:false}}")
    private String shadowMode;

    public boolean useV2(Map<String, Object> request) {
        String requested = CatalogMapSupport.firstNonBlank(
                request, "search_engine_version", "search_engine", "engine", "engine_version");
        if (!requested.isBlank()) {
            return "v2".equalsIgnoreCase(requested.trim());
        }
        return "v2".equalsIgnoreCase(version());
    }

    public boolean shadowMode() {
        return truthy(shadowMode);
    }

    public String version() {
        String value = configuredVersion == null || configuredVersion.isBlank() ? "v1" : configuredVersion.trim();
        return value.toLowerCase(Locale.ROOT);
    }

    private static boolean truthy(String value) {
        if (value == null) {
            return false;
        }
        return List.of("1", "true", "yes", "on", "v2").contains(value.trim().toLowerCase(Locale.ROOT));
    }
}
