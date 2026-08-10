package cz.bankintel.sources.arad;

import cz.bankintel.service.sources.SourceAradIndicatorService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AradSetIndicatorsService {

    private static final long CACHE_TTL_MS = 300_000L;

    private final SourceAradIndicatorService sourceAradIndicatorService;
    private final AradIndicatorHttpSupport aradIndicatorHttpSupport;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public Map<String, Object> getSetIndicators(String setId) {
        String sid = stringOrBlank(setId);
        if (!sid.chars().allMatch(Character::isDigit)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Neplatné set_id");
        }
        CacheEntry cachedPayload = cache.get(sid);
        long now = System.currentTimeMillis();
        if (cachedPayload != null && now - cachedPayload.loadedAtMs() < CACHE_TTL_MS) {
            return cachedPayload.payload();
        }
        String sourceId = sourceAradIndicatorService.findSourceIdForSetId(sid);
        if (!sourceId.isBlank()) {
            List<Map<String, Object>> cached = sourceAradIndicatorService.listIndicators(sourceId);
            if (!cached.isEmpty()) {
                return cacheAndReturn(sid, Map.of(
                        "set_id", sid,
                        "source_id", sourceId,
                        "has_source", true,
                        "from_cache", true,
                        "indicators", toCompactIndicators(cached)));
            }
        }
        if (!aradIndicatorHttpSupport.apiKeyConfigured()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Chybí ARAD API klíč (proměnná ARAD_API_KEY nebo uložený zdroj ARAD s přihlášením).");
        }
        List<Map<String, Object>> raw;
        try {
            raw = aradIndicatorHttpSupport.fetchIndicators(sid);
        } catch (Exception ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Nepodařilo se načíst ukazatele z ČNB ARAD.");
        }
        List<Map<String, Object>> indicators = new ArrayList<>();
        for (Map<String, Object> ind : raw) {
            Map<String, Object> row = AradIndicatorHttpSupport.serializeIndicator(ind);
            if (row != null) {
                indicators.add(row);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("set_id", sid);
        out.put("source_id", sourceId.isBlank() ? null : sourceId);
        out.put("has_source", !sourceId.isBlank());
        out.put("from_cache", false);
        out.put("indicators", indicators);
        return cacheAndReturn(sid, out);
    }

    private static List<Map<String, Object>> toCompactIndicators(List<Map<String, Object>> cached) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : cached) {
            Map<String, Object> compact = AradIndicatorHttpSupport.serializeIndicator(row);
            if (compact != null) {
                out.add(compact);
            }
        }
        return out;
    }

    private static String stringOrBlank(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    private Map<String, Object> cacheAndReturn(String setId, Map<String, Object> payload) {
        cache.put(setId, new CacheEntry(System.currentTimeMillis(), payload));
        return payload;
    }

    private record CacheEntry(long loadedAtMs, Map<String, Object> payload) {}
}
