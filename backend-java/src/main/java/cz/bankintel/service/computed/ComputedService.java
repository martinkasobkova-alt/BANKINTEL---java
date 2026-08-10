package cz.bankintel.service.computed;

import cz.bankintel.domain.dto.AdminDtos.ComputedIndicatorCreateRequest;
import cz.bankintel.domain.entity.ComputedIndicatorEntity;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.ComputedIndicatorRepository;
import cz.bankintel.repository.UserRepository;
import cz.bankintel.service.access.FeatureAccessService;
import cz.bankintel.service.calculations.ComputedIndicatorRunner;
import cz.bankintel.util.IdGenerator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ComputedService {

    private final ComputedIndicatorRepository computedRepository;
    private final UserRepository userRepository;
    private final FeatureAccessService featureAccessService;
    private final ComputedIndicatorRunner computedIndicatorRunner;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listComputed(UserEntity user) {
        Set<String> adminIds = adminUserIds();
        return computedRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(doc -> isVisible(doc, user, adminIds))
                .map(this::toPublic)
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getComputed(String computedId, UserEntity user) {
        ComputedIndicatorEntity doc = computedRepository
                .findById(computedId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nenalezeno"));
        if (!isVisible(doc, user, adminUserIds())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Nenalezeno");
        }
        return toPublic(doc);
    }

    @Transactional
    public Map<String, Object> createComputed(ComputedIndicatorCreateRequest request, UserEntity admin) {
        requireCompositeFeature(admin, request.operation());
        if (computedRepository.existsByName(request.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Výpočet '" + request.name() + "' už existuje");
        }
        ComputedIndicatorEntity entity = fromRequest(request);
        entity.setId(IdGenerator.newId());
        entity.setCreatedByUserId(admin.getId());
        computedRepository.save(entity);
        return toPublic(entity);
    }

    @Transactional
    public Map<String, Object> updateComputed(String computedId, ComputedIndicatorCreateRequest request, UserEntity admin) {
        ComputedIndicatorEntity existing = computedRepository
                .findById(computedId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nenalezeno"));
        if (computedRepository.existsByNameAndIdNot(request.name(), computedId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Výpočet '" + request.name() + "' už existuje");
        }
        requireCompositeFeature(admin, request.operation());
        applyRequest(existing, request);
        existing.setUpdatedAt(Instant.now());
        computedRepository.save(existing);
        return toPublic(existing);
    }

    @Transactional
    public Map<String, Object> deleteComputed(String computedId) {
        if (!computedRepository.existsById(computedId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Nenalezeno");
        }
        computedRepository.deleteById(computedId);
        return Map.of("ok", true);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> runComputed(String computedId, UserEntity user) {
        ComputedIndicatorEntity doc = computedRepository
                .findById(computedId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nenalezeno"));
        if (!isVisible(doc, user, adminUserIds())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Nenalezeno");
        }
        if ("multi".equals(doc.getOperation())) {
            featureAccessService.requireFeature(user, "composite_charts");
        }
        return buildRunResult(doc, user);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> previewComputed(Map<String, Object> payload, UserEntity admin) {
        String operation = str(payload.get("operation"));
        requireCompositeFeature(admin, operation);
        ComputedIndicatorEntity doc = new ComputedIndicatorEntity();
        doc.setName(str(payload.get("name")).isBlank() ? "Náhled" : str(payload.get("name")));
        doc.setOperation(operation);
        doc.setLeft(map(payload.get("left")));
        doc.setRight(map(payload.get("right")));
        doc.setSeries(listOfMaps(payload.get("series")));
        doc.setUnit(str(payload.get("unit")));
        doc.setDescription(str(payload.get("description")));
        doc.setOptions(map(payload.get("options")));

        Map<String, Object> out = buildRunResult(doc, admin);
        out.put("name", doc.getName());
        return out;
    }

    private Map<String, Object> buildRunResult(ComputedIndicatorEntity doc, UserEntity user) {
        String userId = user != null ? user.getId() : null;
        ComputedIndicatorRunner.RunResult result = computedIndicatorRunner.run(doc, userId);
        return computedIndicatorRunner.toRunResponse(doc, result);
    }

    private void requireCompositeFeature(UserEntity user, String operation) {
        if ("multi".equals(str(operation))) {
            featureAccessService.requireFeature(user, "composite_charts");
        }
    }

    private Set<String> adminUserIds() {
        return userRepository.findAllByRoleIgnoreCase("admin").stream()
                .map(UserEntity::getId)
                .collect(Collectors.toSet());
    }

    private boolean isVisible(ComputedIndicatorEntity doc, UserEntity user, Set<String> adminIds) {
        String owner = doc.getCreatedByUserId() != null ? doc.getCreatedByUserId().strip() : "";
        if (owner.isEmpty()) {
            return true;
        }
        if (user == null) {
            return false;
        }
        if ("admin".equalsIgnoreCase(user.getRole())) {
            return true;
        }
        if (user.getId().equals(owner)) {
            return true;
        }
        return adminIds.contains(owner);
    }

    private ComputedIndicatorEntity fromRequest(ComputedIndicatorCreateRequest request) {
        ComputedIndicatorEntity entity = new ComputedIndicatorEntity();
        applyRequest(entity, request);
        return entity;
    }

    private void applyRequest(ComputedIndicatorEntity entity, ComputedIndicatorCreateRequest request) {
        entity.setName(request.name().strip());
        entity.setOperation(request.operation().strip());
        entity.setLeft(request.left() != null ? request.left() : Map.of());
        entity.setRight(request.right() != null ? request.right() : Map.of());
        entity.setSeries(request.series() != null ? request.series() : List.of());
        entity.setDescription(request.description() != null ? request.description() : "");
        entity.setUnit(request.unit() != null ? request.unit() : "");
        entity.setOptions(request.options() != null ? request.options() : Map.of());
    }

    private Map<String, Object> toPublic(ComputedIndicatorEntity entity) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", entity.getId());
        out.put("name", entity.getName());
        out.put("operation", entity.getOperation());
        out.put("left", entity.getLeft() != null ? entity.getLeft() : Map.of());
        out.put("right", entity.getRight() != null ? entity.getRight() : Map.of());
        out.put("series", entity.getSeries() != null ? entity.getSeries() : List.of());
        out.put("description", entity.getDescription() != null ? entity.getDescription() : "");
        out.put("unit", entity.getUnit() != null ? entity.getUnit() : "");
        out.put("created_at", entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null);
        out.put("updated_at", entity.getUpdatedAt() != null ? entity.getUpdatedAt().toString() : null);
        out.put("options", entity.getOptions() != null ? entity.getOptions() : Map.of());
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> m) {
            return new LinkedHashMap<>((Map<String, Object>) m);
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                out.add(new LinkedHashMap<>((Map<String, Object>) m));
            }
        }
        return out;
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value).strip() : "";
    }
}
