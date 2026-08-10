package cz.bankintel.search.analytics;

import cz.bankintel.search.forecast.ForecastPredictorConfig;
import cz.bankintel.search.forecast.ForecastPlannerService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Resolves which deterministic calculation bundles to run for a target series, based on the
 * curated domain ontology ({@link ForecastPredictorConfig}) and the per-domain playbooks in
 * {@link AnalyticsPlaybookConfig}. No LLM is involved — query text only influences domain
 * detection via substring match on {@code match_terms}, same as the forecast planner.
 */
@Service
@RequiredArgsConstructor
public class AnalyticsPlannerService {

    private final ForecastPlannerService forecastPlannerService;

    public record PlanResult(
            Optional<String> domainKey,
            Optional<String> domainLabelCz,
            List<String> calculationTypes,
            String benchmarkGroup,
            String playbookNotes,
            List<ForecastPlannerService.PredictorCandidate> relationshipCandidates) {}

    public PlanResult plan(String targetLabel, String targetSourceType, String targetSetId, String geoHint) {
        return plan(targetLabel, targetSourceType, targetSetId, geoHint, true);
    }

    public PlanResult plan(
            String targetLabel,
            String targetSourceType,
            String targetSetId,
            String geoHint,
            boolean includeRelationshipCandidates) {
        Optional<ForecastPredictorConfig.Domain> domain = ForecastPredictorConfig.get().resolveDomain(targetLabel);
        if (domain.isEmpty()) {
            AnalyticsPlaybookConfig playbookConfig = AnalyticsPlaybookConfig.get();
            return new PlanResult(
                    Optional.empty(),
                    Optional.empty(),
                    playbookConfig.defaultCalculationTypes(),
                    null,
                    "Doména nebyla rozpoznána — spuštěny obecné výpočty (basic_metrics, trend, anomálie).",
                    List.of());
        }

        ForecastPredictorConfig.Domain d = domain.get();
        AnalyticsPlaybookConfig.Playbook playbook =
                AnalyticsPlaybookConfig.get().playbookForDomain(d.key()).orElse(null);
        List<String> calcTypes = playbook != null && !playbook.calculationTypes().isEmpty()
                ? playbook.calculationTypes()
                : AnalyticsPlaybookConfig.get().defaultCalculationTypes();
        String benchmarkGroup = playbook != null ? playbook.benchmarkGroup() : null;
        String notes = playbook != null ? playbook.notes() : "";

        ForecastPlannerService.PlanResult forecastPlan = includeRelationshipCandidates
                ? forecastPlannerService.plan(targetLabel, targetSourceType, targetSetId, geoHint)
                : forecastPlannerService.planDomainOnly(targetLabel);

        return new PlanResult(
                Optional.of(d.key()),
                Optional.of(d.labelCz()),
                calcTypes,
                benchmarkGroup,
                notes,
                forecastPlan.candidates());
    }

    public Map<String, Object> planSummary(PlanResult plan) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("domain", plan.domainKey().orElse(null));
        out.put("domain_label_cz", plan.domainLabelCz().orElse(null));
        out.put("calculation_types", plan.calculationTypes());
        out.put("benchmark_group", plan.benchmarkGroup());
        out.put("playbook_notes", plan.playbookNotes());
        out.put(
                "relationship_candidates",
                plan.relationshipCandidates().stream()
                        .map(c -> Map.of(
                                "role", c.role(),
                                "concept_cz", c.conceptCz(),
                                "source_type", c.sourceType(),
                                "set_id", c.setId(),
                                "title", c.title()))
                        .toList());
        return out;
    }
}
