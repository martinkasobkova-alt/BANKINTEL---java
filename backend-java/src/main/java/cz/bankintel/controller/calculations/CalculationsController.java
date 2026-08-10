package cz.bankintel.controller.calculations;

import cz.bankintel.security.AdminAccess;
import cz.bankintel.service.calculations.CalculationComputeService;
import cz.bankintel.service.calculations.CalculationPlannerService;
import cz.bankintel.service.calculations.CalculationRunService;
import cz.bankintel.service.calculations.ScaleSuggestionService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/calculations")
@RequiredArgsConstructor
public class CalculationsController {

    private final AdminAccess adminAccess;
    private final CalculationComputeService computeService;
    private final CalculationPlannerService plannerService;
    private final CalculationRunService runService;

    @PostMapping("/compute")
    public Map<String, Object> compute(@RequestBody Map<String, Object> body) {
        var admin = adminAccess.requireAdmin();
        try {
            return computeService.compute(body, admin.getId());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Výpočet se nepodařil.");
        }
    }

    @PostMapping("/plan")
    public Map<String, Object> plan(@RequestBody Map<String, Object> body) {
        adminAccess.requireAdmin();
        String question = body.get("question") != null ? String.valueOf(body.get("question")).strip() : "";
        return plannerService.planCalculation(question);
    }

    @PostMapping("/suggest-scale")
    public Map<String, Object> suggestScale(@RequestBody Map<String, Object> body) {
        adminAccess.requireAdmin();
        Map<String, Object> out = ScaleSuggestionService.suggestScaleFactors(toDouble(body.get("scale_a")), toDouble(body.get("scale_b")));
        out.put("applied", false);
        return out;
    }

    @PostMapping("/run")
    public Map<String, Object> run(@RequestBody Map<String, Object> body) {
        var admin = adminAccess.requireAdmin();
        try {
            return runService.run(body, admin.getId());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Výpočet se nepodařil provést.");
        }
    }

    private static Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
