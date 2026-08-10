package cz.bankintel.service.homepage;

import cz.bankintel.domain.entity.HomepageConfigEntity;
import cz.bankintel.repository.HomepageConfigRepository;
import cz.bankintel.service.homepage.resolver.SourceRecordsWidgetResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class HomepageHeadlineKpiService {

    private static final String CONFIG_ID = "main";

    private final HomepageConfigRepository configRepository;
    private final SourceRecordsWidgetResolver sourceRecordsWidgetResolver;

    @Transactional(readOnly = true)
    public Map<String, Object> resolveHeadlineKpis() {
        HomepageConfigEntity config = configRepository.findById(CONFIG_ID).orElse(null);
        List<Map<String, Object>> kpis = config != null && config.getHeadlineKpis() != null ? config.getHeadlineKpis() : List.of();
        List<Map<String, Object>> resolved = new ArrayList<>();
        for (Map<String, Object> kpi : kpis) {
            resolved.add(resolveOne(kpi));
        }
        return Map.of("kpis", resolved);
    }

    private Map<String, Object> resolveOne(Map<String, Object> kpi) {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("id", kpi.getOrDefault("id", ""));
        base.put("title", kpi.getOrDefault("title", ""));
        base.put("value", null);
        base.put("unit", "");
        base.put("period", null);
        base.put("prev_value", null);
        base.put("prev_period", null);
        base.put("trend", "neutral");
        String type = String.valueOf(kpi.getOrDefault("type", "")).strip();
        @SuppressWarnings("unchecked")
        Map<String, Object> cfg = kpi.get("config") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        if ("arad_view".equals(type)) {
            Map<String, Object> data = sourceRecordsWidgetResolver.resolveAradView(cfg, null);
            if (data.containsKey("error")) {
                base.put("error", data.get("error"));
                return base;
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) data.getOrDefault("rows", List.of());
            if (rows.isEmpty()) {
                base.put("error", "Žádná data.");
                return base;
            }
            Map<String, Object> last = rows.get(rows.size() - 1);
            Object value = last.get("value");
            if (value == null) {
                value = last.get("y");
            }
            base.put("value", value);
            base.put("period", last.getOrDefault("period", last.get("x")));
            base.put("unit", data.getOrDefault("unit", ""));
            if (rows.size() > 1) {
                Map<String, Object> prev = rows.get(rows.size() - 2);
                Object prevValue = prev.get("value");
                if (prevValue == null) {
                    prevValue = prev.get("y");
                }
                base.put("prev_value", prevValue);
                base.put("prev_period", prev.getOrDefault("period", prev.get("x")));
                if (value instanceof Number n && prevValue instanceof Number p) {
                    base.put("trend", n.doubleValue() >= p.doubleValue() ? "up" : "down");
                }
            }
        }
        return base;
    }
}
