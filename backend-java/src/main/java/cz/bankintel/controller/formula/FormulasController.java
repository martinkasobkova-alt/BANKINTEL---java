package cz.bankintel.controller.formula;

import cz.bankintel.domain.dto.AdminDtos.FormulaCreateRequest;
import cz.bankintel.security.AdminAccess;
import cz.bankintel.service.formula.FormulaService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/formulas")
@RequiredArgsConstructor
public class FormulasController {

    private final FormulaService formulaService;
    private final AdminAccess adminAccess;

    @GetMapping({"", "/"})
    public List<Map<String, Object>> listFormulas() {
        return formulaService.listFormulas();
    }

    @PostMapping({"", "/"})
    public Map<String, Object> createFormula(@Valid @RequestBody FormulaCreateRequest request) {
        return formulaService.createFormula(request, adminAccess.requireAdmin());
    }

    @DeleteMapping("/{formulaId}")
    public Map<String, Object> deleteFormula(@PathVariable String formulaId) {
        adminAccess.requireAdmin();
        return formulaService.deleteFormula(formulaId);
    }

    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody Map<String, Object> payload) {
        Object expression = payload.get("expression");
        return formulaService.validateExpression(expression != null ? String.valueOf(expression) : "");
    }

    @GetMapping("/{formulaId}/run")
    public Map<String, Object> runFormula(@PathVariable String formulaId) {
        return formulaService.runFormula(formulaId);
    }
}
