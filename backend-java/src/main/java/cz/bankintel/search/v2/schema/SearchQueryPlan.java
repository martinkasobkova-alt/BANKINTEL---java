package cz.bankintel.search.v2.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SearchQueryPlan(
        String originalQuery,
        String language,
        String intent,
        List<String> primaryConcepts,
        List<String> supportingConcepts,
        List<String> geographies,
        List<String> explicitSources,
        List<String> frequencyPreferences,
        List<String> unitPreferences,
        String timeScope,
        List<String> exactSearchTerms,
        List<String> semanticSearchTerms,
        List<String> translatedSearchTerms,
        List<String> relatedSearchTerms,
        List<String> excludedMeanings,
        List<String> desiredResultRoles,
        Clarification clarification,
        String plannerStatus,
        String model,
        ExactEntityResolution entityResolution,
        SourceRoutingDecision sourceRouting,
        List<SearchQueryVariant> queryVariants,
        Map<String, Object> llmPlannerTrace,
        Map<String, Object> fallbackTrace,
        List<String> institutionalSectors,
        List<String> metricIntents) {

    /**
     * Deterministically detected from the user's own wording (never the LLM planner) - see
     * {@code SearchV2InstitutionalSectorRegistry.resolve}. A routing SIGNAL alongside {@code
     * primaryConcepts}, not a replacement for it: {@code SearchV2SectorRoutingGuard} compares this
     * against the sector a resolved concept implies to decide whether concept-driven source routing
     * needs a safety fan-out. The semantic validator's own sector conflict checks are unchanged by
     * this field - it remains the last line of defense regardless of what routing decided.
     */
    public List<String> institutionalSectors() {
        return institutionalSectors == null ? List.of() : institutionalSectors;
    }

    /**
     * Deterministically detected financial metric intent (debt, profitability, cost, assets...) - see
     * {@code SearchV2MetricIntentRegistry.resolve}. A separate axis alongside {@code
     * institutionalSectors}/{@code primaryConcepts}: sector says WHO the query is about, this says
     * WHAT is being asked about them. Consumed only as a RANKING signal by {@code
     * SearchV2FinalReranker} - never a retrieval gate. Blank/empty is a valid, common state
     * ("free_metric_intent" - the query names no metric this registry recognizes); retrieval and
     * ranking still work in that case via plain lexical/vector overlap with the query's own words.
     */
    public List<String> metricIntents() {
        return metricIntents == null ? List.of() : metricIntents;
    }

    public SearchQueryPlan(
            String originalQuery,
            String language,
            String intent,
            List<String> primaryConcepts,
            List<String> supportingConcepts,
            List<String> geographies,
            List<String> explicitSources,
            List<String> frequencyPreferences,
            List<String> unitPreferences,
            String timeScope,
            List<String> exactSearchTerms,
            List<String> semanticSearchTerms,
            List<String> translatedSearchTerms,
            List<String> relatedSearchTerms,
            List<String> excludedMeanings,
            List<String> desiredResultRoles,
            Clarification clarification,
            String plannerStatus,
            String model,
            ExactEntityResolution entityResolution,
            SourceRoutingDecision sourceRouting,
            List<SearchQueryVariant> queryVariants,
            Map<String, Object> llmPlannerTrace,
            Map<String, Object> fallbackTrace,
            List<String> institutionalSectors) {
        this(
                originalQuery,
                language,
                intent,
                primaryConcepts,
                supportingConcepts,
                geographies,
                explicitSources,
                frequencyPreferences,
                unitPreferences,
                timeScope,
                exactSearchTerms,
                semanticSearchTerms,
                translatedSearchTerms,
                relatedSearchTerms,
                excludedMeanings,
                desiredResultRoles,
                clarification,
                plannerStatus,
                model,
                entityResolution,
                sourceRouting,
                queryVariants,
                llmPlannerTrace,
                fallbackTrace,
                institutionalSectors,
                List.of());
    }

    public SearchQueryPlan(
            String originalQuery,
            String language,
            String intent,
            List<String> primaryConcepts,
            List<String> supportingConcepts,
            List<String> geographies,
            List<String> explicitSources,
            List<String> frequencyPreferences,
            List<String> unitPreferences,
            String timeScope,
            List<String> exactSearchTerms,
            List<String> semanticSearchTerms,
            List<String> translatedSearchTerms,
            List<String> relatedSearchTerms,
            List<String> excludedMeanings,
            List<String> desiredResultRoles,
            Clarification clarification,
            String plannerStatus,
            String model,
            ExactEntityResolution entityResolution,
            SourceRoutingDecision sourceRouting,
            List<SearchQueryVariant> queryVariants,
            Map<String, Object> llmPlannerTrace,
            Map<String, Object> fallbackTrace) {
        this(
                originalQuery,
                language,
                intent,
                primaryConcepts,
                supportingConcepts,
                geographies,
                explicitSources,
                frequencyPreferences,
                unitPreferences,
                timeScope,
                exactSearchTerms,
                semanticSearchTerms,
                translatedSearchTerms,
                relatedSearchTerms,
                excludedMeanings,
                desiredResultRoles,
                clarification,
                plannerStatus,
                model,
                entityResolution,
                sourceRouting,
                queryVariants,
                llmPlannerTrace,
                fallbackTrace,
                List.of());
    }

    public SearchQueryPlan(
            String originalQuery,
            String language,
            String intent,
            List<String> primaryConcepts,
            List<String> supportingConcepts,
            List<String> geographies,
            List<String> explicitSources,
            List<String> frequencyPreferences,
            List<String> unitPreferences,
            String timeScope,
            List<String> exactSearchTerms,
            List<String> semanticSearchTerms,
            List<String> translatedSearchTerms,
            List<String> relatedSearchTerms,
            List<String> excludedMeanings,
            List<String> desiredResultRoles,
            Clarification clarification,
            String plannerStatus,
            String model,
            ExactEntityResolution entityResolution,
            SourceRoutingDecision sourceRouting,
            List<SearchQueryVariant> queryVariants) {
        this(
                originalQuery,
                language,
                intent,
                primaryConcepts,
                supportingConcepts,
                geographies,
                explicitSources,
                frequencyPreferences,
                unitPreferences,
                timeScope,
                exactSearchTerms,
                semanticSearchTerms,
                translatedSearchTerms,
                relatedSearchTerms,
                excludedMeanings,
                desiredResultRoles,
                clarification,
                plannerStatus,
                model,
                entityResolution,
                sourceRouting,
                queryVariants,
                Map.of(),
                Map.of());
    }

    public SearchQueryPlan(
            String originalQuery,
            String language,
            String intent,
            List<String> primaryConcepts,
            List<String> supportingConcepts,
            List<String> geographies,
            List<String> explicitSources,
            List<String> frequencyPreferences,
            List<String> unitPreferences,
            String timeScope,
            List<String> exactSearchTerms,
            List<String> semanticSearchTerms,
            List<String> translatedSearchTerms,
            List<String> relatedSearchTerms,
            List<String> excludedMeanings,
            List<String> desiredResultRoles,
            Clarification clarification,
            String plannerStatus,
            String model) {
        this(
                originalQuery,
                language,
                intent,
                primaryConcepts,
                supportingConcepts,
                geographies,
                explicitSources,
                frequencyPreferences,
                unitPreferences,
                timeScope,
                exactSearchTerms,
                semanticSearchTerms,
                translatedSearchTerms,
                relatedSearchTerms,
                excludedMeanings,
                desiredResultRoles,
                clarification,
                plannerStatus,
                model,
                ExactEntityResolution.openTopic("Exact entity resolver was not attached to this plan."),
                SourceRoutingDecision.empty(),
                List.of(),
                Map.of(),
                Map.of());
    }

    public record Clarification(boolean required, String question, String reason) {
        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("required", required);
            out.put("question", question);
            out.put("reason", reason);
            return out;
        }
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("original_query", originalQuery);
        out.put("language", language);
        out.put("intent", intent);
        out.put("primary_concepts", primaryConcepts);
        out.put("supporting_concepts", supportingConcepts);
        out.put("geographies", geographies);
        out.put("explicit_sources", explicitSources);
        out.put("frequency_preferences", frequencyPreferences);
        out.put("unit_preferences", unitPreferences);
        out.put("time_scope", timeScope);
        out.put("exact_search_terms", exactSearchTerms);
        out.put("semantic_search_terms", semanticSearchTerms);
        out.put("translated_search_terms", translatedSearchTerms);
        out.put("related_search_terms", relatedSearchTerms);
        out.put("excluded_meanings", excludedMeanings);
        out.put("desired_result_roles", desiredResultRoles);
        out.put("clarification", clarification != null ? clarification.toMap() : new Clarification(false, null, null).toMap());
        out.put("planner_status", plannerStatus);
        out.put("model", model);
        out.put("entity_resolution", entityResolution != null ? entityResolution.toMap() : ExactEntityResolution.openTopic("").toMap());
        out.put("source_routing", sourceRouting != null ? sourceRouting.toMap() : SourceRoutingDecision.empty().toMap());
        out.put("query_variants", queryVariants == null ? List.of() : queryVariants.stream().map(SearchQueryVariant::toMap).toList());
        out.put("llm_planner", llmPlannerTrace == null ? Map.of() : llmPlannerTrace);
        out.put("fallback_trace", fallbackTrace == null ? Map.of() : fallbackTrace);
        out.put("institutional_sectors", institutionalSectors());
        out.put("metric_intents", metricIntents());
        return out;
    }

    public SearchQueryPlan withPlannerTrace(Map<String, Object> llmTrace, Map<String, Object> deterministicFallbackTrace) {
        return new SearchQueryPlan(
                originalQuery,
                language,
                intent,
                primaryConcepts,
                supportingConcepts,
                geographies,
                explicitSources,
                frequencyPreferences,
                unitPreferences,
                timeScope,
                exactSearchTerms,
                semanticSearchTerms,
                translatedSearchTerms,
                relatedSearchTerms,
                excludedMeanings,
                desiredResultRoles,
                clarification,
                plannerStatus,
                model,
                entityResolution,
                sourceRouting,
                queryVariants,
                llmTrace == null ? Map.of() : llmTrace,
                deterministicFallbackTrace == null ? Map.of() : deterministicFallbackTrace,
                institutionalSectors,
                metricIntents);
    }

    public List<String> allSearchTerms() {
        List<String> terms = new ArrayList<>();
        append(terms, exactSearchTerms);
        append(terms, primaryConcepts);
        append(terms, semanticSearchTerms);
        append(terms, translatedSearchTerms);
        append(terms, relatedSearchTerms);
        append(terms, supportingConcepts);
        return terms.stream()
                .map(s -> s == null ? "" : s.trim())
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
    }

    public boolean highConfidenceExactEntity() {
        return entityResolution != null && entityResolution.highConfidenceExact();
    }

    public List<String> firstPassSearchTerms() {
        if (!highConfidenceExactEntity()) {
            return allSearchTerms();
        }
        List<String> terms = new ArrayList<>();
        if (queryVariants != null) {
            for (SearchQueryVariant variant : queryVariants) {
                if (variant != null && variant.firstPassExactRole()) {
                    terms.add(variant.text());
                }
            }
        }
        append(terms, exactSearchTerms);
        if (entityResolution != null) {
            terms.add(entityResolution.canonicalName());
            append(terms, entityResolution.symbols());
            append(terms, entityResolution.exactTerms());
        }
        return terms.stream()
                .map(s -> s == null ? "" : s.trim())
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
    }

    private static void append(List<String> target, List<String> values) {
        if (values != null) {
            target.addAll(values);
        }
    }

    public static SearchQueryPlan fallback(String query, String status, List<String> sources) {
        String q = query == null ? "" : query.trim();
        return new SearchQueryPlan(
                q,
                looksCzech(q) ? "cs" : "en",
                "find_series",
                List.of(q),
                List.of(),
                List.of(),
                sources == null ? List.of() : sources,
                List.of(),
                List.of(),
                null,
                List.of(q),
                List.of(q),
                List.of(),
                List.of(),
                List.of(),
                List.of("primary"),
                new Clarification(false, null, null),
                status,
                null,
                ExactEntityResolution.openTopic("Fallback plan without exact entity."),
                SourceRoutingDecision.empty(),
                List.of(new SearchQueryVariant(q, "original_exact", 1.0)));
    }

    private static boolean looksCzech(String value) {
        String lower = value.toLowerCase();
        return lower.matches(".*[áčďéěíňóřšťúůýž].*")
                || lower.contains("cesk")
                || lower.contains("czech")
                || lower.contains("cr ");
    }
}
