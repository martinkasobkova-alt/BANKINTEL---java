package cz.bankintel.sources.arad;

import cz.bankintel.domain.dto.SourceDtos.SourceCreateRequest;
import cz.bankintel.repository.SourceRepository;
import cz.bankintel.service.sources.SourceAradIndicatorService;
import cz.bankintel.service.sources.SourceService;
import cz.bankintel.service.sync.SyncService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AradCatalogWriteService {

    private static final String ARAD_BASE_URL = "https://www.cnb.cz/aradb/api/v1";
    private static final String ARAD_DATA_ENDPOINT = "/data";

    private final AradCatalogService aradCatalogService;
    private final SourceService sourceService;
    private final SourceRepository sourceRepository;
    private final SourceAradIndicatorService sourceAradIndicatorService;
    private final SyncService syncService;

    @SuppressWarnings("unchecked")
    public Map<String, Object> addSource(Map<String, Object> payload) {
        String setId = stringOrBlank(payload.get("set_id"));
        if (!setId.chars().allMatch(Character::isDigit)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Neplatné set_id");
        }
        if (!AradIndicatorHttpSupport.apiKeyConfiguredStatic()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Není nakonfigurován ARAD API klíč (proměnná ARAD_API_KEY).");
        }
        Map<String, Object> catalog = aradCatalogService.getCatalogEnvelope();
        Object itemsObj = catalog.get("items");
        String fullName = "";
        if (itemsObj instanceof List<?> items) {
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                if (setId.equals(String.valueOf(map.get("id")))) {
                    Object nameObj = map.get("name");
                    if (nameObj instanceof Map<?, ?> nameMap) {
                        fullName = stringOrBlank(nameMap.get("cs"));
                    } else {
                        fullName = stringOrBlank(nameObj);
                    }
                    break;
                }
            }
        }
        String shortName = fullName.contains(">") ? fullName.substring(fullName.lastIndexOf('>') + 1).trim() : fullName;
        shortName = shortName.replaceAll("\\s*\\(\\d+\\)\\s*$", "").trim();
        String displayName = stringOrBlank(payload.get("name"));
        if (displayName.isBlank()) {
            displayName = shortName.isBlank() ? "ARAD · sestava " + setId : "ARAD · " + shortName + " (" + setId + ")";
        }
        if (sourceRepository.existsByName(displayName)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Zdroj '" + displayName + "' už existuje.");
        }
        Integer refresh = toInteger(payload.get("refresh_interval_minutes"));
        if (refresh == null) {
            refresh = 1440;
        }
        Boolean active = toBoolean(payload.get("active"));
        if (active == null) {
            active = true;
        }
        Map<String, Object> queryParams = new LinkedHashMap<>();
        queryParams.put("set_id", setId);
        queryParams.put("lang", "CS");
        queryParams.put("api_key", AradIndicatorHttpSupport.resolveApiKey());
        SourceCreateRequest request = new SourceCreateRequest(
                displayName,
                "arad",
                ARAD_BASE_URL,
                ARAD_DATA_ENDPOINT,
                "GET",
                "api_key_query",
                Map.of("api_key", AradIndicatorHttpSupport.resolveApiKey(), "param_name", "api_key"),
                Map.of("Accept", "application/json"),
                queryParams,
                refresh,
                active,
                displayName,
                null);
        Map<String, Object> created = sourceService.createSource(request);
        String sourceId = stringOrBlank(created.get("id"));
        if (!sourceId.isBlank()) {
            try {
                sourceAradIndicatorService.refreshIndicators(sourceId);
            } catch (Exception ignored) {
                // sync can populate later
            }
            if (active) {
                syncService.queueSync(sourceId);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", created.get("id"));
        out.put("name", displayName);
        out.put("set_id", setId);
        out.put("arad_full_path", fullName);
        return out;
    }

    private static Integer toInteger(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return value != null ? Integer.parseInt(String.valueOf(value).trim()) : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Boolean toBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value == null) {
            return null;
        }
        return "true".equalsIgnoreCase(String.valueOf(value).trim());
    }

    private static String stringOrBlank(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }
}
