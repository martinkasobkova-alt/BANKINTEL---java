package cz.bankintel.service.homepage.resolver;

import cz.bankintel.repository.DatasetRepository;
import cz.bankintel.repository.FormulaRepository;
import cz.bankintel.repository.RecordRepository;
import cz.bankintel.repository.SourceRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class KpiWidgetResolver {

    private final SourceRepository sourceRepository;
    private final DatasetRepository datasetRepository;
    private final FormulaRepository formulaRepository;
    private final RecordRepository recordRepository;

    public Map<String, Object> resolve(String type) {
        return switch (type) {
            case "kpi_active_sources" -> activeSources();
            case "kpi_datasets" -> datasets();
            case "kpi_records" -> records();
            case "kpi_sync_errors" -> syncErrors();
            default -> Map.of("value", 0);
        };
    }

    private Map<String, Object> activeSources() {
        long total = sourceRepository.count();
        long active = sourceRepository.countByActiveTrue();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("value", active);
        out.put("hint", total + " celkem");
        return out;
    }

    private Map<String, Object> datasets() {
        long datasets = datasetRepository.count();
        long formulas = formulaRepository.count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("value", datasets);
        out.put("hint", formulas + " vzorců");
        return out;
    }

    private Map<String, Object> records() {
        long count = recordRepository.count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("value", count);
        out.put("hint", "napříč všemi sadami");
        return out;
    }

    private Map<String, Object> syncErrors() {
        long err = sourceRepository.countByLastSyncStatus("error");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("value", err);
        out.put("hint", "konektorů v chybě");
        out.put("trend", err == 0 ? "down" : "up");
        return out;
    }
}
