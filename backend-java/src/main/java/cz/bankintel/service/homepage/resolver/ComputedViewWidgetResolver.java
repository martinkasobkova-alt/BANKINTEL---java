package cz.bankintel.service.homepage.resolver;

import cz.bankintel.domain.entity.ComputedIndicatorEntity;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.ComputedIndicatorRepository;
import cz.bankintel.service.calculations.ComputedIndicatorRunner;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ComputedViewWidgetResolver {

    private final ComputedIndicatorRepository computedRepository;
    private final ComputedIndicatorRunner computedIndicatorRunner;

    public Map<String, Object> resolve(Map<String, Object> cfg, UserEntity user) {
        String computedId = str(cfg.get("computed_id"));
        if (computedId.isBlank()) {
            return Map.of("error", "Vyber vlastní výpočet.");
        }
        ComputedIndicatorEntity doc = computedRepository.findById(computedId).orElse(null);
        if (doc == null) {
            return Map.of("error", "Výpočet neexistuje.");
        }
        String view = str(cfg.get("view")).isBlank() ? "chart" : str(cfg.get("view")).toLowerCase(Locale.ROOT);
        int limit = parseLimit(cfg.get("limit"));
        String userId = user != null ? user.getId() : null;
        ComputedIndicatorRunner.RunResult result = computedIndicatorRunner.run(doc, userId);
        List<Map<String, Object>> rawRows = result.rows();
        if (rawRows.isEmpty()) {
            return Map.of("error", "Výpočet nevrátil data.", "warnings", result.warnings());
        }
        if (limit > 0 && rawRows.size() > limit) {
            rawRows = rawRows.subList(rawRows.size() - limit, rawRows.size());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", doc.getName());
        out.put("unit", doc.getUnit() != null ? doc.getUnit() : "");
        out.put("view", view);
        if ("multi".equals(doc.getOperation())) {
            out.put("multi_series", true);
            out.put("series", doc.getSeries() != null ? doc.getSeries() : List.of());
        }
        if ("chart".equals(view)) {
            List<Map<String, Object>> chartRows = new ArrayList<>();
            for (Map<String, Object> row : rawRows) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("x", row.get("period"));
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    if (!"period".equals(entry.getKey())) {
                        item.put(entry.getKey(), entry.getValue());
                    }
                }
                chartRows.add(item);
            }
            out.put("rows", chartRows);
        } else {
            out.put("rows", rawRows);
        }
        return out;
    }

    private static int parseLimit(Object raw) {
        if (raw == null) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(String.valueOf(raw).strip()));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value).strip() : "";
    }
}
