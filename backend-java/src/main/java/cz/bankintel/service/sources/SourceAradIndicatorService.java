package cz.bankintel.service.sources;

import cz.bankintel.domain.entity.AradIndicatorEntity;
import cz.bankintel.domain.entity.SourceEntity;
import cz.bankintel.repository.AradIndicatorRepository;
import cz.bankintel.repository.SourceRepository;
import cz.bankintel.sources.arad.AradIndicatorHttpSupport;
import cz.bankintel.util.IdGenerator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class SourceAradIndicatorService {

    private final SourceRepository sourceRepository;
    private final AradIndicatorRepository aradIndicatorRepository;
    private final AradIndicatorHttpSupport aradIndicatorHttpSupport;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listIndicators(String sourceId) {
        requireAradSource(sourceId);
        return aradIndicatorRepository.findBySourceIdOrderByIndicatorIdAsc(sourceId).stream()
                .map(this::serializeEntity)
                .toList();
    }

    @Transactional
    public Map<String, Object> refreshIndicators(String sourceId) {
        SourceEntity source = requireAradSource(sourceId);
        String setId = extractSetId(source);
        if (setId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ARAD source missing set_id");
        }
        List<Map<String, Object>> raw;
        try {
            raw = aradIndicatorHttpSupport.fetchIndicators(setId);
        } catch (Exception ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Nepodařilo se načíst ukazatele z ČNB ARAD: " + ex.getMessage());
        }
        aradIndicatorRepository.deleteBySourceId(sourceId);
        int written = 0;
        Instant now = Instant.now();
        for (Map<String, Object> ind : raw) {
            String iid = stringOrBlank(ind.get("indicator_id"));
            if (iid.isBlank()) {
                continue;
            }
            AradIndicatorEntity entity = new AradIndicatorEntity();
            entity.setId(IdGenerator.newId());
            entity.setSourceId(sourceId);
            entity.setIndicatorId(iid);
            entity.setName(stringOrBlank(ind.get("indicator_name")).isBlank()
                    ? stringOrBlank(ind.get("name"))
                    : stringOrBlank(ind.get("indicator_name")));
            entity.setFrequencyCode(stringOrBlank(ind.get("frequency_code")));
            entity.setFrequencyName(stringOrBlank(ind.get("frequency_name")));
            entity.setUnit(stringOrBlank(ind.get("unit")));
            entity.setUnitMult(stringOrBlank(ind.get("unit_mult_name")));
            entity.setFetchedAt(now);
            aradIndicatorRepository.save(entity);
            written++;
        }
        return Map.of("refreshed", written);
    }

    @Transactional(readOnly = true)
    public String findSourceIdForSetId(String setId) {
        String sid = stringOrBlank(setId);
        if (sid.isBlank()) {
            return "";
        }
        for (SourceEntity source : sourceRepository.findBySourceTypeOrderByNameAsc("arad")) {
            if (sid.equals(extractSetId(source))) {
                return source.getId();
            }
        }
        return "";
    }

    private SourceEntity requireAradSource(String sourceId) {
        SourceEntity source = sourceRepository
                .findById(sourceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found"));
        if (!"arad".equalsIgnoreCase(source.getSourceType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not an ARAD source");
        }
        return source;
    }

    @SuppressWarnings("unchecked")
    private static String extractSetId(SourceEntity source) {
        Map<String, Object> config = source.getConnectorConfig();
        if (config == null) {
            return "";
        }
        Object qp = config.get("query_params");
        if (qp instanceof Map<?, ?> map) {
            return stringOrBlank(map.get("set_id"));
        }
        return "";
    }

    private Map<String, Object> serializeEntity(AradIndicatorEntity entity) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source_id", entity.getSourceId());
        out.put("indicator_id", entity.getIndicatorId());
        out.put("name", entity.getName());
        out.put("frequency_code", entity.getFrequencyCode());
        out.put("frequency_name", entity.getFrequencyName());
        out.put("unit", entity.getUnit());
        out.put("unit_mult", entity.getUnitMult());
        out.put("fetched_at", entity.getFetchedAt() != null ? entity.getFetchedAt().toString() : null);
        return out;
    }

    private static String stringOrBlank(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }
}
