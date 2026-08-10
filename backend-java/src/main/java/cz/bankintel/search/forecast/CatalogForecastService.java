package cz.bankintel.search.forecast;

import cz.bankintel.search.CatalogIndexStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Orchestrates {@code POST /api/catalog/forecast}: plan predictors, assemble normalized series,
 * run the in-process Java forecast engine and attach a deterministic Czech narrative. Domain
 * behavior stays registry driven through {@code forecast_predictors.json}.
 */
@Service
@RequiredArgsConstructor
public class CatalogForecastService {

    private final CatalogIndexStore indexStore;
    private final ForecastPlannerService plannerService;
    private final ForecastDataAssemblerService assemblerService;
    private final ForecastModelEngine forecastModelEngine;
    private final ForecastNarrativeService narrativeService;

    @SuppressWarnings("unchecked")
    public Map<String, Object> forecast(Map<String, Object> body) {
        String sourceType = str(body.get("source_type"));
        String setId = str(body.get("set_id"));
        if (sourceType.isBlank() || setId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing source_type or set_id");
        }
        String nameHint = str(body.get("name"));
        String geoHint = str(body.get("geo"));
        List<String> horizons = body.get("horizons") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();
        Map<String, Object> queryParams =
                body.get("query_params") instanceof Map<?, ?> map ? castMap(map) : Map.of();
        String selectedIndicator = str(body.get("selected_indicator"));
        List<String> selectedIndicators = body.get("selected_indicators") instanceof List<?> selectedList
                ? selectedList.stream().map(String::valueOf).filter(s -> !s.isBlank()).toList()
                : (selectedIndicator.isBlank() ? List.of() : List.of(selectedIndicator));
        String primaryIndicator = firstNonBlank(
                selectedIndicator,
                selectedIndicators.isEmpty() ? "" : selectedIndicators.getFirst());
        List<String> targetIndicators = primaryIndicator.isBlank() ? List.of() : List.of(primaryIndicator);
        int candidateFetchBudgetMs = intValue(body.get("candidate_fetch_budget_ms"), 0);
        boolean includeCandidateSearch = booleanValue(body.get("include_candidate_search"), true);
        String targetFrequency = str(body.get("target_frequency"));

        Map<String, Object> indexRow = indexStore.lookupRow(sourceType, setId).orElse(Map.of());
        String labelForDomainMatch = firstNonBlank(nameHint, str(indexRow.get("title")), str(indexRow.get("name")), setId);
        if (geoHint.isBlank()) {
            geoHint = firstNonBlank(str(indexRow.get("geo_label")), str(indexRow.get("geo")), str(indexRow.get("country_label")));
        }

        Map<String, Object> targetDimensionFilters =
                body.get("dimension_filters") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();

        ForecastPlannerService.PlanResult plan = includeCandidateSearch
                ? plannerService.plan(labelForDomainMatch, sourceType, setId, geoHint)
                : plannerService.planDomainOnly(labelForDomainMatch);
        Map<String, Object> requestPayload = assemblerService.buildRequest(
                sourceType,
                setId,
                nameHint.isBlank() ? labelForDomainMatch : nameHint,
                geoHint,
                queryParams,
                targetDimensionFilters,
                primaryIndicator,
                targetIndicators,
                plan.candidates(),
                horizons,
                candidateFetchBudgetMs,
                targetFrequency);

        Map<String, Object> response = forecastModelEngine.forecast(requestPayload);
        Map<String, Object> narrative = narrativeService.buildNarrative(response, plan.domainLabelCz().orElse(null));
        Map<String, Object> out = new LinkedHashMap<>(response);
        out.put("narrative", narrative);
        out.put("planner", plannerSummary(plan));
        out.put("target_resolution", targetResolution(labelForDomainMatch, geoHint, response));
        out.put("target_selection", targetSelection(
                sourceType,
                setId,
                nameHint.isBlank() ? labelForDomainMatch : nameHint,
                primaryIndicator,
                targetDimensionFilters));
        return out;
    }

    private static Map<String, Object> targetSelection(
            String sourceType,
            String setId,
            String name,
            String selectedIndicator,
            Map<String, Object> dimensionFilters) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source_type", sourceType);
        out.put("set_id", setId);
        out.put("name", name);
        out.put("selected_indicator", selectedIndicator.isBlank() ? null : selectedIndicator);
        out.put("dimension_filters", new LinkedHashMap<>(dimensionFilters));
        out.put("strict", true);
        return out;
    }

    /**
     * Assembles the "how did we map this query onto an economic concept" audit block: which
     * ontology domain matched (or none), the geography we searched candidates for, the
     * frequency/unit actually resolved by the Java forecast engine, the ontology's advisory
     * preferred-series hints, and which horizons actually got forecast. This is intentionally
     * assembled here from static ontology and the already-computed response.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> targetResolution(String labelForDomainMatch, String geoHint, Map<String, Object> response) {
        Optional<ForecastPredictorConfig.Domain> domain = ForecastPredictorConfig.get().resolveDomain(labelForDomainMatch);
        Map<String, Object> targetSeries =
                response.get("target_series") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        List<Object> forecastPoints = response.get("forecast") instanceof List<?> l ? (List<Object>) l : List.of();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("target_concept", domain.map(ForecastPredictorConfig.Domain::key).orElse(null));
        out.put("geo", (geoHint == null || geoHint.isBlank()) ? null : geoHint);
        out.put("frequency", targetSeries.get("frequency"));
        out.put("unit", targetSeries.get("unit"));
        out.put("preferred_series", domain.map(ForecastPredictorConfig.Domain::preferredSeriesHint).orElse(List.of()));
        out.put(
                "forecast_horizons",
                forecastPoints.stream()
                        .filter(o -> o instanceof Map)
                        .map(o -> String.valueOf(((Map<?, ?>) o).get("horizon")))
                        .toList());
        return out;
    }

    private static Map<String, Object> plannerSummary(ForecastPlannerService.PlanResult plan) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("domain", plan.domainKey().orElse(null));
        out.put("domain_label_cz", plan.domainLabelCz().orElse(null));
        out.put(
                "predictors_considered",
                plan.candidates().stream()
                        .map(c -> Map.of(
                                "role", c.role(),
                                "concept_cz", c.conceptCz(),
                                "source_type", c.sourceType(),
                                "set_id", c.setId(),
                                "title", c.title()))
                        .toList());
        return out;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value != null) {
            String text = String.valueOf(value).trim().toLowerCase();
            if ("true".equals(text) || "1".equals(text) || "yes".equals(text)) {
                return true;
            }
            if ("false".equals(text) || "0".equals(text) || "no".equals(text)) {
                return false;
            }
        }
        return fallback;
    }

    private static Map<String, Object> castMap(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }
}
