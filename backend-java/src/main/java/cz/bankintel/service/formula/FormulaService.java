package cz.bankintel.service.formula;

import cz.bankintel.domain.dto.AdminDtos.FormulaCreateRequest;
import cz.bankintel.domain.entity.FormulaEntity;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.FormulaRepository;
import cz.bankintel.service.access.FeatureAccessService;
import cz.bankintel.util.IdGenerator;
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
public class FormulaService {

    private final FormulaRepository formulaRepository;
    private final FeatureAccessService featureAccessService;
    private final FormulaEvaluator formulaEvaluator;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listFormulas() {
        return formulaRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toPublic)
                .toList();
    }

    @Transactional
    public Map<String, Object> createFormula(FormulaCreateRequest request, UserEntity admin) {
        featureAccessService.requireFeature(admin, "saved_calculations");
        var validation = FormulaExpressionValidator.validate(request.expression());
        if (!validation.ok()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, validation.message());
        }
        if (formulaRepository.existsByName(request.name())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Formula name already exists");
        }
        FormulaEntity entity = new FormulaEntity();
        entity.setId(IdGenerator.newId());
        entity.setName(request.name().strip());
        entity.setExpression(request.expression().strip());
        entity.setGroupBy(request.groupBy() != null && !request.groupBy().isEmpty()
                ? request.groupBy()
                : List.of("date"));
        entity.setDatasets(request.datasets() != null ? request.datasets() : List.of());
        entity.setDescription(request.description() != null ? request.description() : "");
        formulaRepository.save(entity);
        return toPublic(entity);
    }

    @Transactional
    public Map<String, Object> deleteFormula(String formulaId) {
        if (!formulaRepository.existsById(formulaId)) {
            return Map.of("deleted", 0);
        }
        formulaRepository.deleteById(formulaId);
        return Map.of("deleted", 1);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> runFormula(String formulaId) {
        FormulaEntity formula = formulaRepository
                .findById(formulaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Formula not found"));
        Map<String, Object> result = computeFormula(formula);
        Map<String, Object> out = new LinkedHashMap<>(result);
        out.put("formula", toPublic(formula));
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> computeFormula(FormulaEntity formula) {
        return formulaEvaluator.compute(formula);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> computeFormulaByName(String name, List<String> groupByOverride) {
        return formulaRepository
                .findByName(name)
                .map(formula -> {
                    if (groupByOverride != null) {
                        FormulaEntity copy = new FormulaEntity();
                        copy.setId(formula.getId());
                        copy.setName(formula.getName());
                        copy.setExpression(formula.getExpression());
                        copy.setGroupBy(groupByOverride);
                        copy.setDatasets(formula.getDatasets());
                        copy.setDescription(formula.getDescription());
                        copy.setCreatedAt(formula.getCreatedAt());
                        return computeFormula(copy);
                    }
                    return computeFormula(formula);
                })
                .orElse(Map.of("rows", List.of(), "total", 0.0));
    }

    public Map<String, Object> validateExpression(String expression) {
        var result = FormulaExpressionValidator.validate(expression != null ? expression : "");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", result.ok());
        out.put("message", result.message());
        return out;
    }

    private Map<String, Object> toPublic(FormulaEntity entity) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", entity.getId());
        out.put("name", entity.getName());
        out.put("expression", entity.getExpression());
        out.put("group_by", entity.getGroupBy() != null ? entity.getGroupBy() : List.of("date"));
        out.put("datasets", entity.getDatasets() != null ? entity.getDatasets() : new ArrayList<>());
        out.put("description", entity.getDescription());
        out.put("created_at", entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null);
        return out;
    }
}
