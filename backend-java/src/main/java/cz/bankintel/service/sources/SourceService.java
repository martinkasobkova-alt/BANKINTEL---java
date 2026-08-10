package cz.bankintel.service.sources;

import cz.bankintel.domain.dto.SourceDtos.SourceCreateRequest;
import cz.bankintel.domain.dto.SourceDtos.SourceUpdateRequest;
import cz.bankintel.domain.entity.SourceEntity;
import cz.bankintel.repository.SourceRepository;
import cz.bankintel.util.IdGenerator;
import java.util.HashMap;
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
public class SourceService {

    private final SourceRepository sourceRepository;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listSources() {
        return sourceRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(SourceMapper::toPublic)
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getSource(String id) {
        SourceEntity source = sourceRepository
                .findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found"));
        return SourceMapper.toPublic(source);
    }

    @Transactional
    public Map<String, Object> createSource(SourceCreateRequest request) {
        if (sourceRepository.existsByName(request.name())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Source name already exists");
        }
        SourceEntity source = new SourceEntity();
        source.setId(IdGenerator.newId());
        source.setName(request.name());
        source.setSourceType(request.sourceType());
        source.setBaseUrl(request.baseUrl() != null ? request.baseUrl() : "");
        source.setEndpoint(request.endpoint() != null ? request.endpoint() : "");
        source.setMethod(request.method() != null ? request.method() : "GET");
        source.setAuthType(request.authType() != null ? request.authType() : "none");
        source.setRefreshIntervalMinutes(request.refreshIntervalMinutes() != null ? request.refreshIntervalMinutes() : 60);
        source.setActive(request.active() != null ? request.active() : true);
        source.setDatasetName(request.datasetName() != null ? request.datasetName() : request.name());
        source.setConnectorConfig(ConnectorRegistry.buildConnectorConfig(
                request.headers(), request.queryParams(), request.credentials()));
        sourceRepository.save(source);
        return SourceMapper.toPublic(source);
    }

    @Transactional
    public Map<String, Object> updateSource(String id, SourceUpdateRequest request) {
        SourceEntity source = sourceRepository
                .findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found"));

        if (request.name() != null && !request.name().equals(source.getName())) {
            if (sourceRepository.existsByNameAndIdNot(request.name(), id)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Source name already exists");
            }
            source.setName(request.name());
        }
        if (request.baseUrl() != null) {
            source.setBaseUrl(request.baseUrl());
        }
        if (request.endpoint() != null) {
            source.setEndpoint(request.endpoint());
        }
        if (request.method() != null) {
            source.setMethod(request.method());
        }
        if (request.authType() != null) {
            source.setAuthType(request.authType());
        }
        if (request.refreshIntervalMinutes() != null) {
            source.setRefreshIntervalMinutes(request.refreshIntervalMinutes());
        }
        if (request.active() != null) {
            source.setActive(request.active());
        }
        if (request.datasetName() != null) {
            source.setDatasetName(request.datasetName());
        }

        if (request.headers() != null || request.queryParams() != null || request.credentials() != null) {
            Map<String, Object> config = new HashMap<>(source.getConnectorConfig() != null ? source.getConnectorConfig() : Map.of());
            if (request.headers() != null) {
                config.put("headers", request.headers());
            }
            if (request.queryParams() != null) {
                config.put("query_params", request.queryParams());
            }
            if (request.credentials() != null) {
                config.put("credentials", request.credentials());
            }
            source.setConnectorConfig(config);
        }

        sourceRepository.save(source);
        return SourceMapper.toPublic(source);
    }

    @Transactional
    public Map<String, Object> deleteSource(String id) {
        if (!sourceRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found");
        }
        sourceRepository.deleteById(id);
        return Map.of("deleted", 1);
    }

    public Map<String, Object> listTypes() {
        return Map.of("types", ConnectorRegistry.availableTypes());
    }
}
