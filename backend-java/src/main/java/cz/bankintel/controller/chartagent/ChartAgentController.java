package cz.bankintel.controller.chartagent;

import cz.bankintel.service.chartagent.ChartAgentIntentService;
import cz.bankintel.service.chartagent.ChartAgentService;
import cz.bankintel.service.chartagent.ChartContractParser;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/chart-agent")
@RequiredArgsConstructor
public class ChartAgentController {

    private final ChartAgentIntentService intentService;
    private final ChartAgentService chartAgentService;

    @PostMapping("/intent")
    public Map<String, Object> intent(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body != null ? body : Map.of();
        String question = ChartContractParser.str(payload.get("question"));
        if (question.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chybí otázka.");
        }
        try {
            return intentService.interpretIntent(payload);
        } catch (Exception ex) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Porozumění dotazu se nepodařilo: " + ex.getMessage());
        }
    }

    @PostMapping("/ask")
    public Map<String, Object> ask(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body != null ? body : Map.of();
        String question = ChartContractParser.str(payload.get("question"));
        if (question.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chybí otázka.");
        }
        if (!(payload.get("chart_contract") instanceof Map<?, ?>)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chybí chart_contract.");
        }
        try {
            return chartAgentService.analyzeChartQuestion(payload);
        } catch (Exception ex) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Analýza grafu se nepodařila: " + ex.getMessage());
        }
    }
}
