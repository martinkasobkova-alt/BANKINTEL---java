package cz.bankintel.service.me;

import cz.bankintel.domain.dto.AdminDtos.ComputedIndicatorCreateRequest;
import cz.bankintel.domain.entity.ComputedIndicatorEntity;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.ComputedIndicatorRepository;
import cz.bankintel.service.access.FeatureAccessService;
import cz.bankintel.service.computed.ComputedService;
import cz.bankintel.util.IdGenerator;
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
public class MeComputedService {

    private final ComputedIndicatorRepository computedRepository;
    private final ComputedService computedService;
    private final FeatureAccessService featureAccessService;

    @Transactional
    public Map<String, Object> createComputed(UserEntity user, Map<String, Object> payload) {
        requireSavedCalculations(user);
        requireSubscriber(user);
        ComputedIndicatorCreateRequest request = toRequest(payload);
        if ("multi".equalsIgnoreCase(request.operation())) {
            featureAccessService.requireFeature(user, "composite_charts");
        }
        if (computedRepository.existsByName(request.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Výpočet '" + request.name() + "' už existuje");
        }
        ComputedIndicatorEntity entity = new ComputedIndicatorEntity();
        entity.setId(IdGenerator.newId());
        entity.setName(request.name());
        entity.setOperation(request.operation());
        entity.setLeft(request.left() != null ? request.left() : Map.of());
        entity.setRight(request.right() != null ? request.right() : Map.of());
        entity.setSeries(request.series() != null ? request.series() : List.of());
        entity.setDescription(request.description() != null ? request.description() : "");
        entity.setUnit(request.unit() != null ? request.unit() : "");
        entity.setOptions(request.options() != null ? request.options() : Map.of());
        entity.setCreatedByUserId(user.getId());
        computedRepository.save(entity);
        return computedService.getComputed(entity.getId(), user);
    }

    private static ComputedIndicatorCreateRequest toRequest(Map<String, Object> payload) {
        Map<String, Object> body = payload != null ? payload : Map.of();
        String name = stringOrBlank(body.get("name"));
        String operation = stringOrBlank(body.get("operation"));
        if (name.isBlank() || operation.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name and operation are required");
        }
        return new ComputedIndicatorCreateRequest(
                name,
                operation,
                map(body.get("left")),
                map(body.get("right")),
                listOfMaps(body.get("series")),
                stringOrBlank(body.get("description")),
                stringOrBlank(body.get("unit")),
                map(body.get("options")));
    }

    private void requireSavedCalculations(UserEntity user) {
        featureAccessService.requireFeature(user, "saved_calculations");
    }

    private void requireSubscriber(UserEntity user) {
        if (!FeatureAccessService.isSubscriber(user)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Tato funkce je dostupná pro předplatitele časopisu Bankovnictví.");
        }
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
        return list.stream().filter(Map.class::isInstance).map(v -> (Map<String, Object>) v).toList();
    }

    private static String stringOrBlank(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }
}
