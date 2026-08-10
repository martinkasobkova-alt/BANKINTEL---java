package cz.bankintel.service.calculations;

import cz.bankintel.domain.entity.UserSavedSeriesEntity;
import cz.bankintel.repository.UserSavedSeriesRepository;
import cz.bankintel.search.CatalogSourceRegistry;
import cz.bankintel.search.analytics.AnalyticsSeriesLoader;
import cz.bankintel.service.myseries.SavedSeriesResolverService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SeriesOperandLoader {

    private final SavedSeriesResolverService savedSeriesResolverService;
    private final UserSavedSeriesRepository userSavedSeriesRepository;
    private final AnalyticsSeriesLoader analyticsSeriesLoader;

    @SuppressWarnings("unchecked")
    public Map<String, Double> loadSeriesMap(Map<String, Object> operand, String userId) {
        if (operand == null) {
            return Map.of();
        }
        Object inlineValues = operand.get("values");
        if (inlineValues instanceof Map<?, ?> rawValues) {
            Map<String, Double> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawValues.entrySet()) {
                Double val = parseNumber(entry.getValue());
                if (val != null) {
                    out.put(String.valueOf(entry.getKey()), val);
                }
            }
            if (!out.isEmpty()) {
                return out;
            }
        }
        Object pointsObj = operand.get("points");
        if (pointsObj instanceof List<?> points) {
            Map<String, Double> out = new LinkedHashMap<>();
            for (Object ptObj : points) {
                if (ptObj instanceof Map<?, ?> rawPt) {
                    Map<String, Object> pt = (Map<String, Object>) rawPt;
                    Double val = parseNumber(pt.get("value"));
                    String period = str(pt.get("period"));
                    if (val != null && !period.isBlank()) {
                        out.put(period, val);
                    }
                }
            }
            if (!out.isEmpty()) {
                return out;
            }
        }

        Optional<Map<String, Double>> catalogSeries = loadCatalogSeries(operand);
        if (catalogSeries.isPresent()) {
            return catalogSeries.get();
        }

        String savedSeriesId = str(operand.get("saved_series_id"));
        if (!savedSeriesId.isBlank()) {
            if (userId == null || userId.isBlank()) {
                return Map.of();
            }
            UserSavedSeriesEntity saved = userSavedSeriesRepository
                    .findByIdAndUserId(savedSeriesId, userId)
                    .orElse(null);
            if (saved == null) {
                return Map.of();
            }
            Map<String, Double> out = new LinkedHashMap<>();
            for (Map<String, Object> pt : saved.getDataPoints()) {
                Double val = parseNumber(pt.get("value"));
                String period = str(pt.get("period"));
                if (val != null && !period.isBlank()) {
                    out.put(period, val);
                }
            }
            return out;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source_id", operand.get("source_id"));
        payload.put("indicator_id", operand.get("indicator_id"));
        payload.put("x_field", operand.get("x_field"));
        payload.put("y_field", operand.get("y_field"));
        payload.put("unit", operand.get("unit"));
        payload.put("frequency", operand.get("frequency"));
        payload.put("area", operand.get("area"));
        payload.put("category", operand.get("category"));
        if (!str(operand.get("user_upload_id")).isBlank()) {
            payload.put("user_upload_id", operand.get("user_upload_id"));
        }
        if (str(payload.get("source_id")).isBlank()) {
            return Map.of();
        }
        SavedSeriesResolverService.ResolvedPoints resolved =
                savedSeriesResolverService.resolvePoints(userId, payload);
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map<String, Object> pt : resolved.points()) {
            Double val = parseNumber(pt.get("value"));
            String period = str(pt.get("period"));
            if (val != null && !period.isBlank()) {
                out.put(period, val);
            }
        }
        return out;
    }

    /**
     * Catalog search references identify a live connector by {@code source_type + set_id}; they are
     * not UUIDs from the synchronized {@code sources} table. Reuse the same preview pipeline as
     * charts, analytics and forecasts so follow-up calculations work for every supported catalog.
     */
    private Optional<Map<String, Double>> loadCatalogSeries(Map<String, Object> operand) {
        String sourceType = CatalogSourceRegistry.normalizeSearchSource(firstNonBlank(
                str(operand.get("source_type")),
                str(operand.get("catalog_id")),
                str(operand.get("source"))));
        String setId = firstNonBlank(
                str(operand.get("set_id")),
                str(operand.get("series_id")),
                str(operand.get("indicator_id")));
        if (sourceType.isBlank() || setId.isBlank()) {
            return Optional.empty();
        }

        AnalyticsSeriesLoader.LoadedSeries loaded = analyticsSeriesLoader.loadStrict(
                sourceType,
                setId,
                firstNonBlank(str(operand.get("title")), str(operand.get("name")), setId),
                firstNonBlank(str(operand.get("territory")), str(operand.get("geo")), str(operand.get("area"))),
                mapValue(operand.get("query_params")),
                mapValue(operand.get("dimension_filters")),
                str(operand.get("selected_indicator")),
                stringList(operand.get("selected_indicators")),
                str(operand.get("role")));
        return Optional.of(loaded.values());
    }

    public String operandDisplayName(Map<String, Object> operand) {
        if (operand == null) {
            return "Řada";
        }
        String indicatorId = str(operand.get("indicator_id"));
        if (!indicatorId.isBlank()) {
            return indicatorId;
        }
        String yField = str(operand.get("y_field"));
        return yField.isBlank() ? "tabulární řada" : yField;
    }

    private static Double parseNumber(Object value) {
        if (value == null || value instanceof Boolean) {
            return null;
        }
        try {
            double out = Double.parseDouble(String.valueOf(value));
            return Double.isFinite(out) ? out : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((key, item) -> out.put(String.valueOf(key), item));
        return out;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : raw) {
            String text = str(item);
            if (!text.isBlank()) {
                out.add(text);
            }
        }
        return out;
    }
}
