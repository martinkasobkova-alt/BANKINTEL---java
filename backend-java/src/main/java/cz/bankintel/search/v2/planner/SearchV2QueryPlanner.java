package cz.bankintel.search.v2.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.CatalogCountryAliasRegistry;
import cz.bankintel.search.CatalogCountryIso3Registry;
import cz.bankintel.search.CatalogGeoIntent;
import cz.bankintel.search.CatalogSourceRegistry;
import cz.bankintel.search.CatalogTextUtils;
import cz.bankintel.search.model.CatalogMapSupport;
import cz.bankintel.search.openai.OpenAiClient;
import cz.bankintel.search.openai.OpenAiClient.CompletionResult;
import cz.bankintel.search.openai.OpenAiClientException;
import cz.bankintel.search.openai.OpenAiModelTask;
import cz.bankintel.search.v2.entity.ExactEntityResolver;
import cz.bankintel.search.v2.entity.ExactEntityResolver.ResolutionResult;
import cz.bankintel.search.v2.entity.SearchV2SourceCapabilityRegistry;
import cz.bankintel.search.v2.geo.SearchV2GeoCompatibility;
import cz.bankintel.search.v2.ontology.SearchV2ConceptRegistry;
import cz.bankintel.search.v2.ontology.SearchV2ConceptRegistry.ConceptResolution;
import cz.bankintel.search.v2.ontology.SearchV2InstitutionalSectorRegistry;
import cz.bankintel.search.v2.ontology.SearchV2MetricIntentRegistry;
import cz.bankintel.search.v2.schema.ExactEntityResolution;
import cz.bankintel.search.v2.schema.SearchQueryPlan;
import cz.bankintel.search.v2.schema.SearchQueryVariant;
import cz.bankintel.search.v2.schema.SourceRoutingDecision;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SearchV2QueryPlanner {

    public static final List<String> ALL_SEARCH_V2_SOURCES =
            List.of("arad", "csu", "eurostat", "ecb2", "fred", "imf", "data360", "worldbank", "bis", "oecd4", "commodities", "stocks");

    private static final ObjectMapper PROMPT_MAPPER = new ObjectMapper();
    private static final String PROMPT = loadPrompt();

    /**
     * Human-readable prompt version, bumped by hand whenever planner_prompt.md content changes.
     * Kept as a separate constant (not embedded in the .md file) so telemetry versioning never
     * alters what is actually sent to the LLM. {@link #promptContentHash()} is the automatic,
     * tamper-evident cross-check: if the file changes without this constant being bumped, the
     * hash observed in telemetry will silently stop matching this version's expected value.
     */
    public static final String PLANNER_PROMPT_VERSION = "v1-2026-07-24";
    private static final Set<String> GENERIC_SINGLE_TOKENS = Set.of(
            "rate", "mira", "m\u00edra", "index", "value", "hodnota", "data", "rada", "\u0159ada", "series");
    private static final Map<String, List<String>> SOURCE_ALIASES = Map.ofEntries(
            Map.entry("arad", List.of("arad", "cnb arad", "čnb arad", "cnb", "čnb")),
            Map.entry("csu", List.of("csu", "čsú", "czso")),
            Map.entry("eurostat", List.of("eurostat")),
            Map.entry("ecb2", List.of("ecb", "european central bank")),
            Map.entry("fred", List.of("fred")),
            Map.entry("imf", List.of("imf", "mmf", "international monetary fund")),
            Map.entry("data360", List.of("data360", "world bank data360")),
            Map.entry("worldbank", List.of("world bank", "worldbank", "world development indicators", "wdi")),
            Map.entry("bis", List.of("bis")),
            Map.entry("oecd4", List.of("oecd")),
            Map.entry("commodities", List.of("komodity", "commodities")));

    private final OpenAiClient openAiClient;
    private final ExactEntityResolver exactEntityResolver;
    private final SearchV2ConceptRegistry conceptRegistry;
    private final SearchV2SourceCapabilityRegistry sourceCapabilityRegistry;
    private final SearchV2InstitutionalSectorRegistry institutionalSectorRegistry;
    private final SearchV2MetricIntentRegistry metricIntentRegistry;

    /**
     * Deterministic (never LLM-driven) institutional sector detection from the user's own wording -
     * a routing SIGNAL alongside {@code primaryConcepts}, computed the same way regardless of which
     * plan-construction path (exact entity, local fallback, or validated LLM plan) produced the plan.
     */
    private List<String> institutionalSectorsFor(String query) {
        String sector = institutionalSectorRegistry.resolve(query);
        return sector.isBlank() ? List.of() : List.of(sector);
    }

    /**
     * Deterministic (never LLM-driven) financial metric intent detection - a RANKING signal (see
     * {@code SearchV2FinalReranker}), never a retrieval gate. Blank/empty means "free_metric_intent":
     * the query names no metric this registry recognizes, which is a valid, common state - ranking
     * still works via plain lexical/vector overlap with the query's own words in that case.
     */
    private List<String> metricIntentsFor(String query) {
        String metric = metricIntentRegistry.resolve(query);
        return metric.isBlank() ? List.of() : List.of(metric);
    }

    public SearchQueryPlan plan(Map<String, Object> request) {
        String query = CatalogMapSupport.firstNonBlank(request, "q", "query");
        ResolutionResult resolution = exactEntityResolver.resolve(query);
        ConceptResolution conceptResolution = conceptRegistry.resolve(query);
        List<String> requestedSources = normalizeSources(readStringList(request.get("sources")));
        String selectedSource = CatalogSourceRegistry.normalizeSearchSource(
                CatalogMapSupport.firstNonBlank(request, "selected_source", "source", "catalog"));
        if (!selectedSource.isBlank()) {
            requestedSources = List.of(selectedSource);
        }
        List<String> selectedGeo = readSelectedGeo(request);
        SearchQueryPlan fallback =
                localPlan(query, requestedSources, selectedGeo, "local_fallback", resolution, conceptResolution, Map.of(), Map.of());
        if (resolution.entityResolution().highConfidenceExact()) {
            return exactPlan(query, requestedSources, selectedGeo, resolution, "exact_entity_resolver");
        }
        boolean useAi = parseBoolean(request.get("use_ai"), true);
        if (!useAi || !openAiClient.isConfigured()) {
            return fallback;
        }
        try {
            CompletionResult completion = openAiClient.plannerCompletionJson(
                    PROMPT,
                    plannerUserPrompt(
                            query,
                            requestedSources,
                            selectedGeo,
                            request,
                            resolution,
                            conceptResolution,
                            exactEntityResolver.plannerContext(),
                            conceptRegistry.plannerContext()),
                    SearchV2PlannerStructuredOutput.schema());
            JsonNode json = completion.json();
            SearchQueryPlan parsed = fromJson(json, query, requestedSources, selectedGeo);
            return validatePlan(parsed, fallback, resolution, conceptResolution).withPlannerTrace(completion.trace(), Map.of());
        } catch (OpenAiClientException ex) {
            return localPlan(
                    query,
                    requestedSources,
                    selectedGeo,
                    "llm_unavailable:" + ex.errorType().name(),
                    resolution,
                    conceptResolution,
                    ex.trace(),
                    fallbackTrace(ex.errorType().name(), false));
        } catch (Exception ex) {
            return localPlan(
                    query,
                    requestedSources,
                    selectedGeo,
                    "llm_unavailable:" + ex.getClass().getSimpleName(),
                    resolution,
                    conceptResolution,
                    Map.of("called", true, "success", false, "error_type", ex.getClass().getSimpleName(), "fallback_used", true),
                    fallbackTrace(ex.getClass().getSimpleName(), false));
        }
    }

    private SearchQueryPlan validatePlan(
            SearchQueryPlan parsed, SearchQueryPlan fallback, ResolutionResult resolution, ConceptResolution conceptResolution) {
        List<String> explicitSources = !fallback.explicitSources().isEmpty()
                ? fallback.explicitSources()
                : normalizeSources(parsed.explicitSources());
        List<String> geographies = !fallback.geographies().isEmpty() ? fallback.geographies() : cleanList(parsed.geographies(), 6);
        boolean clarify = shouldKeepClarification(parsed, fallback);
        ExactEntityResolution entityResolution = resolution.entityResolution();
        List<String> institutionalSectors = institutionalSectorsFor(fallback.originalQuery());
        List<String> metricIntents = metricIntentsFor(fallback.originalQuery());
        SourceRoutingDecision conceptRouting = conceptResolution.highConfidence()
                ? sourceCapabilityRegistry.routeConcepts(conceptResolution.concepts())
                : SourceRoutingDecision.empty();
        // AI planner nondeterminism audit (2026-07-31, 6 reps x 5 queries): when neither exact-entity
        // nor concept-registry routing fires, source_routing fell through entirely to the LLM's own
        // per-call guess ("planner: strict structured output"), which visibly varied rep-to-rep
        // (empty selected_catalog_families/preferred_sources on some calls) and directly caused a
        // verified-result count to swing 0 vs 4 for the identical "ziskovost penzijnich fondu" query.
        // metricIntentsFor is deterministic and already resolves for these under-covered queries even
        // though the coarser concept ontology has no matching entry - see routeByEntityType's javadoc.
        boolean hasPreciseStructuredRouting = !resolution.sourceRouting().preferredSources().isEmpty()
                || !conceptRouting.preferredSources().isEmpty();
        SourceRoutingDecision metricRouting = !metricIntents.isEmpty() && !hasPreciseStructuredRouting
                ? sourceCapabilityRegistry.routeByEntityType("financial_ratio")
                : SourceRoutingDecision.empty();
        SourceRoutingDecision sourceRouting = mergeSourceRouting(
                resolution.sourceRouting(), conceptRouting, metricRouting, parsed.sourceRouting());
        List<String> primaryConcepts = conceptResolution.highConfidence()
                ? conceptResolution.conceptIds()
                : cleanList(nonEmpty(parsed.primaryConcepts(), fallback.primaryConcepts()), 8);
        List<String> supportingConcepts = conceptResolution.highConfidence()
                ? filterConflictingTerms(parsed.supportingConcepts(), conceptResolution.conceptIds())
                : cleanList(parsed.supportingConcepts(), 8);
        List<SearchQueryVariant> variants =
                filterConflictingVariants(mergeVariants(resolution.queryVariants(), parsed.queryVariants()), primaryConcepts);
        SearchQueryPlan validated = new SearchQueryPlan(
                fallback.originalQuery(),
                blank(parsed.language(), fallback.language()),
                blank(parsed.intent(), clarify ? "ambiguous" : fallback.intent()),
                cleanList(primaryConcepts, 8),
                cleanList(supportingConcepts, 8),
                geographies,
                explicitSources,
                cleanList(parsed.frequencyPreferences(), 4),
                cleanList(parsed.unitPreferences(), 4),
                parsed.timeScope(),
                ensureConceptTerms(
                        filterConflictingTerms(nonEmpty(parsed.exactSearchTerms(), fallback.exactSearchTerms()), primaryConcepts),
                        fallback.originalQuery(),
                        conceptResolution),
                ensureConceptTerms(
                        filterConflictingTerms(nonEmpty(parsed.semanticSearchTerms(), fallback.semanticSearchTerms()), primaryConcepts),
                        fallback.originalQuery(),
                        conceptResolution),
                filterConflictingTerms(parsed.translatedSearchTerms(), primaryConcepts),
                filterConflictingTerms(parsed.relatedSearchTerms(), primaryConcepts),
                cleanList(parsed.excludedMeanings(), 10),
                cleanList(nonEmpty(parsed.desiredResultRoles(), List.of("primary")), 6),
                clarify ? parsed.clarification() : fallback.clarification(),
                "openai",
                openAiClient.modelFor(OpenAiModelTask.PLANNER),
                entityResolution,
                sourceRouting,
                variants,
                Map.of(),
                Map.of(),
                institutionalSectors,
                metricIntents);
        return validated;
    }

    /**
     * Structured source evidence is authoritative. Exact-entity, concept and metric registries may
     * complement each other, but a free-form LLM suggestion must not widen a precise registry route
     * into every catalog and exhaust the retrieval budget. The LLM route is used only when structured
     * evidence has no preferred source; its families and reasons remain available in the trace.
     */
    static SourceRoutingDecision mergeSourceRouting(
            SourceRoutingDecision exact,
            SourceRoutingDecision concept,
            SourceRoutingDecision metric,
            SourceRoutingDecision llm) {
        LinkedHashSet<String> families = new LinkedHashSet<>();
        LinkedHashSet<String> preferred = new LinkedHashSet<>();
        LinkedHashSet<String> excluded = new LinkedHashSet<>();
        Map<String, String> reasons = new LinkedHashMap<>();
        List<SourceRoutingDecision> structured = List.of(
                exact == null ? SourceRoutingDecision.empty() : exact,
                concept == null ? SourceRoutingDecision.empty() : concept,
                metric == null ? SourceRoutingDecision.empty() : metric);
        for (SourceRoutingDecision decision : structured) {
            collectRoutingTrace(decision, families, excluded, reasons);
            preferred.addAll(normalizeSources(decision.preferredSources()));
        }
        if (llm != null) {
            collectRoutingTrace(llm, families, excluded, reasons);
            if (preferred.isEmpty()) {
                preferred.addAll(normalizeSources(llm.preferredSources()));
            }
        }
        excluded.removeAll(preferred);
        return new SourceRoutingDecision(
                new ArrayList<>(families),
                new ArrayList<>(preferred),
                new ArrayList<>(excluded),
                reasons);
    }

    private static void collectRoutingTrace(
            SourceRoutingDecision decision,
            LinkedHashSet<String> families,
            LinkedHashSet<String> excluded,
            Map<String, String> reasons) {
        families.addAll(decision.selectedCatalogFamilies() == null
                ? List.of()
                : decision.selectedCatalogFamilies());
        excluded.addAll(normalizeSources(decision.excludedSources()));
        if (decision.sourceSelectionReason() != null) {
            decision.sourceSelectionReason().forEach(reasons::putIfAbsent);
        }
    }

    private static boolean shouldKeepClarification(SearchQueryPlan parsed, SearchQueryPlan fallback) {
        if (fallback.clarification() != null && fallback.clarification().required()) {
            return true;
        }
        if (parsed.clarification() == null || !parsed.clarification().required()) {
            return false;
        }
        boolean hasConcreteSearchPlan = !cleanList(parsed.primaryConcepts(), 4).isEmpty()
                && (!cleanList(parsed.exactSearchTerms(), 4).isEmpty()
                        || !cleanList(parsed.semanticSearchTerms(), 4).isEmpty()
                        || !cleanList(parsed.translatedSearchTerms(), 4).isEmpty());
        boolean plannerDeclaredAmbiguous = "ambiguous".equalsIgnoreCase(parsed.intent());
        return plannerDeclaredAmbiguous && !hasConcreteSearchPlan;
    }

    private SearchQueryPlan exactPlan(
            String query,
            List<String> requestedSources,
            List<String> selectedGeo,
            ResolutionResult resolution,
            String status) {
        ExactEntityResolution entity = resolution.entityResolution();
        List<String> exactTerms = resolution.queryVariants().stream()
                .filter(SearchQueryVariant::firstPassExactRole)
                .map(SearchQueryVariant::text)
                .distinct()
                .limit(10)
                .toList();
        String q = query == null ? "" : query.trim();
        List<String> explicitSources = requestedSources != null && !requestedSources.isEmpty()
                ? requestedSources
                : explicitSourcesFromQuery(q);
        List<String> geographies = SearchV2GeoCompatibility.mergeWithEntityFixedGeo(
                selectedGeo != null && !selectedGeo.isEmpty() ? selectedGeo : geographiesFromQuery(q),
                entity);
        return new SearchQueryPlan(
                q,
                looksCzech(q) ? "cs" : "en",
                "find_series",
                List.of(entity.canonicalName()),
                List.of(),
                geographies,
                explicitSources,
                List.of(),
                List.of(),
                null,
                cleanList(exactTerms, 10),
                List.of(entity.canonicalName()),
                List.of(),
                entity.relatedEntities(),
                List.of(),
                List.of("primary"),
                new SearchQueryPlan.Clarification(false, null, null),
                status,
                null,
                entity,
                resolution.sourceRouting(),
                resolution.queryVariants(),
                Map.of(),
                Map.of(),
                institutionalSectorsFor(q),
                metricIntentsFor(q));
    }

    private SearchQueryPlan localPlan(
            String query,
            List<String> requestedSources,
            List<String> selectedGeo,
            String status,
            ResolutionResult resolution) {
        return localPlan(query, requestedSources, selectedGeo, status, resolution, conceptRegistry.resolve(query), Map.of(), Map.of());
    }

    private SearchQueryPlan localPlan(
            String query,
            List<String> requestedSources,
            List<String> selectedGeo,
            String status,
            ResolutionResult resolution,
            ConceptResolution conceptResolution,
            Map<String, Object> llmTrace,
            Map<String, Object> fallbackTrace) {
        String q = query == null ? "" : query.trim();
        List<String> explicitSources = requestedSources != null && !requestedSources.isEmpty()
                ? requestedSources
                : explicitSourcesFromQuery(q);
        List<String> geographies = SearchV2GeoCompatibility.mergeWithEntityFixedGeo(
                selectedGeo != null && !selectedGeo.isEmpty() ? selectedGeo : geographiesFromQuery(q),
                resolution.entityResolution());
        SearchQueryPlan.Clarification clarification = clarificationFor(q);
        String intent = clarification.required() ? "ambiguous" : inferIntent(q);
        List<String> terms = conceptResolution.highConfidence()
                ? ensureConceptTerms(localSearchTerms(q), q, conceptResolution)
                : localSearchTerms(q);
        SourceRoutingDecision conceptRouting = conceptResolution.highConfidence()
                ? sourceCapabilityRegistry.routeConcepts(conceptResolution.concepts())
                : resolution.sourceRouting();
        return new SearchQueryPlan(
                q,
                looksCzech(q) ? "cs" : "en",
                intent,
                conceptResolution.highConfidence() ? conceptResolution.conceptIds() : List.of(q),
                List.of(),
                geographies,
                explicitSources,
                List.of(),
                List.of(),
                null,
                terms,
                terms,
                List.of(),
                List.of(),
                List.of(),
                List.of("primary"),
                clarification,
                status,
                null,
                resolution.entityResolution(),
                conceptRouting,
                conceptResolution.highConfidence() ? conceptVariants(q, conceptResolution) : resolution.queryVariants(),
                llmTrace == null ? Map.of() : llmTrace,
                fallbackTrace == null || fallbackTrace.isEmpty() ? fallbackTrace(status, safeToSearch(terms)) : fallbackTrace,
                institutionalSectorsFor(q),
                metricIntentsFor(q));
    }

    private static SearchQueryPlan fromJson(
            JsonNode json, String query, List<String> requestedSources, List<String> selectedGeo) {
        if (json != null && json.has("required_concepts")) {
            return fromStructuredJson(json, query, requestedSources, selectedGeo);
        }
        SearchQueryPlan.Clarification clarification = new SearchQueryPlan.Clarification(
                json.path("clarification").path("required").asBoolean(false),
                textOrNull(json.path("clarification").path("question")),
                textOrNull(json.path("clarification").path("reason")));
        List<String> explicitSources = normalizeSources(readArray(json.get("explicit_sources")));
        if (requestedSources != null && !requestedSources.isEmpty()) {
            explicitSources = requestedSources;
        }
        List<String> geos = normalizeGeographies(readArray(json.get("geographies")));
        if (selectedGeo != null && !selectedGeo.isEmpty()) {
            geos = selectedGeo;
        }
        return new SearchQueryPlan(
                CatalogMapSupport.firstNonBlank(json.path("original_query").asText(""), query),
                json.path("language").asText("cs"),
                json.path("intent").asText("find_series"),
                readArray(json.get("primary_concepts")),
                readArray(json.get("supporting_concepts")),
                geos,
                explicitSources,
                readArray(json.get("frequency_preferences")),
                readArray(json.get("unit_preferences")),
                textOrNull(json.path("time_scope")),
                readArray(json.get("exact_search_terms")),
                readArray(json.get("semantic_search_terms")),
                readArray(json.get("translated_search_terms")),
                readArray(json.get("related_search_terms")),
                readArray(json.get("excluded_meanings")),
                readArray(json.get("desired_result_roles")),
                clarification,
                "openai",
                null,
                parseEntityResolution(json.path("entity_resolution")),
                parseSourceRouting(json.path("source_routing"), json),
                parseQueryVariants(json.path("query_variants")));
    }

    private static SearchQueryPlan fromStructuredJson(
            JsonNode json, String query, List<String> requestedSources, List<String> selectedGeo) {
        List<String> explicitSources = requestedSources != null && !requestedSources.isEmpty()
                ? requestedSources
                : List.of();
        List<String> geos = normalizeGeographies(readArray(json.get("geographies")));
        if (selectedGeo != null && !selectedGeo.isEmpty()) {
            geos = selectedGeo;
        }
        List<String> requiredConcepts = cleanList(readArray(json.get("required_concepts")), 8);
        List<String> measureTypes = cleanList(readArray(json.get("measure_types")), 8);
        boolean clarificationRequired = json.path("clarification_required").asBoolean(false);
        List<SearchQueryVariant> plannedVariants = parseQueryVariants(json.path("query_variants"));
        List<SearchQueryVariant> clarificationVariants = clarificationVariants(json.path("clarification_options"));
        List<SearchQueryVariant> variants = clarificationRequired
                ? prioritizeClarificationVariants(plannedVariants, clarificationVariants)
                : mergeVariants(plannedVariants, clarificationVariants);
        List<String> variantTerms = variants.stream()
                .map(SearchQueryVariant::text)
                .filter(text -> text != null && !text.isBlank())
                .distinct()
                .limit(8)
                .toList();
        List<String> exactTerms = variantTerms.isEmpty()
                ? cleanList(List.of(query), 1)
                : variantTerms;
        SourceRoutingDecision routing = new SourceRoutingDecision(
                cleanList(readArray(json.get("catalog_families")), 8),
                normalizeSources(readArray(json.get("preferred_sources"))),
                normalizeSources(readArray(json.get("excluded_sources"))),
                Map.of("planner", "strict structured output"));
        SearchQueryPlan.Clarification clarification = new SearchQueryPlan.Clarification(
                clarificationRequired,
                textOrNull(json.path("clarification_question")),
                clarificationRequired ? "LLM planner requested clarification." : null);
        return new SearchQueryPlan(
                CatalogMapSupport.firstNonBlank(json.path("normalized_query").asText(""), query),
                looksCzech(query) ? "cs" : "en",
                mapStructuredIntent(json.path("intent").asText("lookup")),
                requiredConcepts,
                measureTypes,
                geos,
                explicitSources,
                List.of(),
                List.of(),
                null,
                exactTerms,
                cleanList(nonEmpty(requiredConcepts, exactTerms), 8),
                List.of(),
                cleanList(measureTypes, 8),
                List.of(),
                List.of("primary"),
                clarification,
                "openai",
                null,
                ExactEntityResolution.openTopic("Structured planner handled this as an open-topic query."),
                routing,
                variants);
    }

    private static List<SearchQueryVariant> prioritizeClarificationVariants(
            List<SearchQueryVariant> planned,
            List<SearchQueryVariant> options) {
        List<SearchQueryVariant> originals = planned == null
                ? List.of()
                : planned.stream().filter(SearchQueryVariant::firstPassExactRole).limit(1).toList();
        return mergeVariants(mergeVariants(originals, options), planned);
    }

    private static List<SearchQueryVariant> clarificationVariants(JsonNode optionsNode) {
        return cleanList(readArray(optionsNode), 8).stream()
                .map(option -> new SearchQueryVariant(option, "professional_synonym", 0.55))
                .toList();
    }

    private static String mapStructuredIntent(String intent) {
        return switch (intent == null ? "" : intent.trim().toLowerCase(Locale.ROOT)) {
            case "compare" -> "compare";
            case "relationship" -> "relationship";
            case "forecast" -> "forecast";
            case "ambiguous" -> "ambiguous";
            default -> "find_series";
        };
    }

    private static String plannerUserPrompt(
            String query,
            List<String> requestedSources,
            List<String> selectedGeo,
            Map<String, Object> request,
            ResolutionResult resolution,
            ConceptResolution conceptResolution,
            Map<String, Object> plannerContext,
            Map<String, Object> conceptRegistryContext) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", query);
        payload.put("selected_sources", requestedSources);
        payload.put("selected_geo", selectedGeo);
        payload.put(
                "geo_memberships",
                SearchV2GeoCompatibility.membershipsFor(
                        selectedGeo != null && !selectedGeo.isEmpty() ? selectedGeo : geographiesFromQuery(query)));
        payload.put("pre_llm_entity_resolution", resolution.toMap());
        payload.put("pre_llm_concept_resolution", conceptResolution.toMap());
        payload.put(
                "source_capability_registry",
                compactSourceCapabilities(plannerContext.getOrDefault("source_capabilities", Map.of())));
        payload.put("concept_registry", compactConceptRegistry(conceptRegistryContext, conceptResolution));
        payload.put("ui_context", request.getOrDefault("ui_context", Map.of()));
        try {
            return PROMPT_MAPPER.writeValueAsString(payload);
        } catch (Exception ex) {
            return payload.toString();
        }
    }

    private static Map<String, Object> compactConceptRegistry(
            Map<String, Object> conceptRegistryContext, ConceptResolution conceptResolution) {
        if (conceptRegistryContext == null || conceptRegistryContext.isEmpty()) {
            return Map.of();
        }
        List<String> supportedConcepts = conceptRegistryContext.keySet().stream()
                .map(String::valueOf)
                .filter(value -> !value.isBlank())
                .sorted()
                .toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("supported_concepts", supportedConcepts);

        LinkedHashSet<String> focusedIds = new LinkedHashSet<>();
        if (conceptResolution != null && conceptResolution.highConfidence()) {
            focusedIds.addAll(conceptResolution.conceptIds());
            for (String id : List.copyOf(focusedIds)) {
                focusedIds.addAll(stringListFromObject(asMap(conceptRegistryContext.get(id)).get("compatible_concepts")));
            }
        }

        if (!focusedIds.isEmpty()) {
            out.put("focused_concepts", compactConcepts(conceptRegistryContext, focusedIds));
        }
        return out;
    }

    private static Map<String, Object> compactSourceCapabilities(Object sourceCapabilities) {
        Map<String, Object> rawSources = asMap(sourceCapabilities);
        if (rawSources.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        rawSources.keySet().stream().sorted().forEach(source -> {
            Map<String, Object> raw = asMap(rawSources.get(source));
            List<String> families = stringListFromObject(raw.get("catalog_families"));
            if (!families.isEmpty()) {
                out.put(source, families);
            }
        });
        return out;
    }

    private static Map<String, Object> compactConcepts(Map<String, Object> context, Iterable<String> ids) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String id : ids) {
            String conceptId = id == null ? "" : id.trim();
            if (conceptId.isBlank() || out.containsKey(conceptId)) {
                continue;
            }
            Map<String, Object> raw = asMap(context.get(conceptId));
            if (raw.isEmpty()) {
                continue;
            }
            out.put(conceptId, Map.of(
                    "catalog_families", stringListFromObject(raw.get("catalog_families")),
                    "entity_types", stringListFromObject(raw.get("entity_types")),
                    "preferred_sources", stringListFromObject(raw.get("preferred_sources")),
                    "compatible_concepts", stringListFromObject(raw.get("compatible_concepts"))));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, value) -> {
                if (key != null) {
                    out.put(String.valueOf(key), value);
                }
            });
            return out;
        }
        return Map.of();
    }

    private static List<String> stringListFromObject(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .limit(12)
                    .toList();
        }
        return List.of();
    }

    private static ExactEntityResolution parseEntityResolution(JsonNode node) {
        if (node == null || !node.isObject()) {
            return ExactEntityResolution.openTopic("Planner did not return entity_resolution.");
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        JsonNode attrNode = node.path("attributes");
        if (attrNode.isObject()) {
            attrNode.fields().forEachRemaining(entry -> attributes.put(entry.getKey(), entry.getValue().asText("")));
        }
        return new ExactEntityResolution(
                node.path("resolution_type").asText("open_topic"),
                node.path("confidence").asDouble(0.0),
                node.path("entity_type").asText(""),
                node.path("canonical_name").asText(""),
                readArray(node.get("symbols")),
                readArray(node.get("aliases")),
                node.path("catalog_family").asText(""),
                normalizeSources(readArray(node.get("preferred_sources"))),
                readArray(node.get("exact_terms")),
                readArray(node.get("related_entities")),
                node.path("allow_broad_expansion").asBoolean(true),
                node.path("reason").asText(""),
                attributes);
    }

    private static SourceRoutingDecision parseSourceRouting(JsonNode node, JsonNode root) {
        JsonNode source = node != null && node.isObject() ? node : root;
        Map<String, String> reasons = new LinkedHashMap<>();
        JsonNode reasonNode = source.path("source_selection_reason");
        if (reasonNode.isObject()) {
            reasonNode.fields().forEachRemaining(entry -> reasons.put(entry.getKey(), entry.getValue().asText("")));
        }
        return new SourceRoutingDecision(
                cleanList(readArray(source.get("selected_catalog_families")), 8),
                normalizeSources(readArray(source.get("preferred_sources"))),
                normalizeSources(readArray(source.get("excluded_sources"))),
                reasons);
    }

    private static List<SearchQueryVariant> parseQueryVariants(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<SearchQueryVariant> out = new ArrayList<>();
        node.forEach(item -> {
            String text = item.path("text").asText("").trim();
            String role = item.path("role").asText("broader_concept").trim();
            double weight = item.path("weight").asDouble(0.5);
            if (!text.isBlank()) {
                out.add(new SearchQueryVariant(text, role, Math.max(0.0, Math.min(1.0, weight))));
            }
        });
        return out.stream().limit(20).toList();
    }

    private static List<SearchQueryVariant> mergeVariants(
            List<SearchQueryVariant> primary,
            List<SearchQueryVariant> secondary) {
        List<SearchQueryVariant> out = new ArrayList<>();
        appendVariants(out, primary);
        appendVariants(out, secondary);
        return out.stream().limit(20).toList();
    }

    private static void appendVariants(List<SearchQueryVariant> out, List<SearchQueryVariant> variants) {
        if (variants == null) {
            return;
        }
        for (SearchQueryVariant variant : variants) {
            if (variant == null || variant.text() == null || variant.text().isBlank()) {
                continue;
            }
            String normalized = CatalogTextUtils.normalizeTokenBoundaries(variant.text());
            boolean exists = out.stream().anyMatch(existing ->
                    CatalogTextUtils.normalizeTokenBoundaries(existing.text()).equals(normalized));
            if (!exists) {
                out.add(variant);
            }
        }
    }

    private static SearchQueryPlan.Clarification clarificationFor(String query) {
        String folded = CatalogTextUtils.foldAscii(query);
        boolean actionLike = startsWithAny(folded, List.of("navysit", "zvysit", "snizit", "omezit", "increase", "decrease"));
        boolean productionLike = containsAny(folded, List.of("vyrobu", "vyroba", "production", "output"));
        boolean asksForData = containsAny(folded, List.of("data", "vyvoj", "casova rada", "trend", "kolik", "rate", "index"));
        if (actionLike && productionLike && !asksForData) {
            return new SearchQueryPlan.Clarification(
                    true,
                    "O jaký typ výroby jde: průmyslovou výrobu, automobilovou výrobu, nebo jiný konkrétní sektor?",
                    "Dotaz zní jako akční záměr a neříká, kterou ekonomickou řadu má systém hledat.");
        }
        return new SearchQueryPlan.Clarification(false, null, null);
    }

    private static String inferIntent(String query) {
        String folded = CatalogTextUtils.foldAscii(query);
        if (containsAny(folded, List.of("porovnej", "compare", "srovnej"))) {
            return "compare";
        }
        if (containsAny(folded, List.of("vztah", "souvisi", "relationship", "driver", "doporuc"))) {
            return "relationship";
        }
        if (containsAny(folded, List.of("forecast", "predikce", "predpoved"))) {
            return "forecast";
        }
        return "find_series";
    }

    private static List<String> localSearchTerms(String query) {
        List<String> terms = new ArrayList<>();
        String q = query == null ? "" : query.trim();
        if (!q.isBlank()) {
            terms.add(q);
        }
        List<String> tokens = CatalogTextUtils.needlesFromQuery(q);
        // Deliberately NOT adding String.join(" ", tokens) as a single search term here (giant-dump
        // fix, A/B tested): joining every needle token from the banking-group synonym expansion into
        // one term reproducibly timed out or returned zero candidates against every structured FTS
        // source, for every one of the 13/68 gold+holdout queries where it fired - it never
        // contributed a single candidate. The individual tokens below are still added on their own,
        // which is what actually finds results. See SearchV2GiantDumpProductionPathTest.
        for (String token : tokens) {
            if (token.length() >= 4 && !isGenericSingleToken(token)) {
                terms.add(token);
            }
        }
        return cleanList(terms, 8);
    }

    private List<String> filterConflictingTerms(List<String> terms, List<String> requiredConceptIds) {
        List<String> out = new ArrayList<>();
        for (String term : terms == null ? List.<String>of() : terms) {
            if (term == null || term.isBlank() || isGenericOnlyTerm(term)) {
                continue;
            }
            if (!requiredConceptIds.isEmpty() && conceptRegistry.conflictsWithRequiredConcepts(term, requiredConceptIds)) {
                continue;
            }
            out.add(term);
        }
        return cleanList(out, 10);
    }

    private List<SearchQueryVariant> filterConflictingVariants(
            List<SearchQueryVariant> variants, List<String> requiredConceptIds) {
        if (variants == null || variants.isEmpty() || requiredConceptIds.isEmpty()) {
            return variants == null ? List.of() : variants;
        }
        List<SearchQueryVariant> out = new ArrayList<>();
        for (SearchQueryVariant variant : variants) {
            if (variant == null || variant.text() == null || variant.text().isBlank()) {
                continue;
            }
            if (conceptRegistry.conflictsWithRequiredConcepts(variant.text(), requiredConceptIds)) {
                continue;
            }
            out.add(variant);
        }
        return out.stream().limit(20).toList();
    }

    private static List<String> ensureConceptTerms(
            List<String> terms, String originalQuery, ConceptResolution conceptResolution) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (originalQuery != null && !originalQuery.isBlank()) {
            out.add(originalQuery.trim());
        }
        for (String term : terms == null ? List.<String>of() : terms) {
            if (term != null && !term.isBlank()) {
                out.add(term.trim());
            }
        }
        if (conceptResolution.highConfidence()) {
            for (var match : conceptResolution.matches()) {
                out.add(match.concept().id());
                for (String alias : match.concept().aliases()) {
                    out.add(alias);
                    if (out.size() >= 10) {
                        return new ArrayList<>(out).subList(0, Math.min(10, out.size()));
                    }
                }
            }
        }
        return new ArrayList<>(out).stream().limit(10).toList();
    }

    private static List<SearchQueryVariant> conceptVariants(String originalQuery, ConceptResolution conceptResolution) {
        List<SearchQueryVariant> out = new ArrayList<>();
        if (originalQuery != null && !originalQuery.isBlank()) {
            out.add(new SearchQueryVariant(originalQuery.trim(), "original_exact", 1.0));
        }
        if (conceptResolution.highConfidence()) {
            for (var match : conceptResolution.matches()) {
                out.add(new SearchQueryVariant(match.concept().id(), "canonical_name", 0.96));
                out.add(new SearchQueryVariant(match.matchedAlias(), "exact_alias", 0.94));
            }
        }
        return out.stream().limit(12).toList();
    }

    private static boolean isGenericOnlyTerm(String term) {
        List<String> tokens = CatalogTextUtils.needlesFromQuery(term);
        return !tokens.isEmpty() && tokens.stream().allMatch(SearchV2QueryPlanner::isGenericSingleToken);
    }

    private static boolean safeToSearch(List<String> terms) {
        if (terms == null || terms.isEmpty()) {
            return false;
        }
        for (String term : terms) {
            if (term == null || term.isBlank()) {
                continue;
            }
            List<String> tokens = CatalogTextUtils.needlesFromQuery(term);
            if (tokens.size() > 1 || tokens.stream().anyMatch(token -> !isGenericSingleToken(token))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isGenericSingleToken(String token) {
        String folded = CatalogTextUtils.foldAscii(token == null ? "" : token).toLowerCase(Locale.ROOT);
        return GENERIC_SINGLE_TOKENS.contains(folded);
    }

    private static Map<String, Object> fallbackTrace(String reason, boolean safeToSearch) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("engine", "deterministic_fallback");
        trace.put("reason", reason == null || reason.isBlank() ? "UNKNOWN" : reason);
        trace.put("concept_confidence", 0.0);
        trace.put("safe_to_search", safeToSearch);
        return trace;
    }

    private static List<String> explicitSourcesFromQuery(String query) {
        String folded = CatalogTextUtils.foldAscii(query);
        List<SourceAliasMatch> matches = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : SOURCE_ALIASES.entrySet()) {
            for (String alias : entry.getValue()) {
                String foldedAlias = CatalogTextUtils.foldAscii(alias);
                if (containsPhrase(folded, foldedAlias)) {
                    matches.add(new SourceAliasMatch(entry.getKey(), foldedAlias));
                }
            }
        }
        matches.sort(java.util.Comparator.comparingInt((SourceAliasMatch match) -> match.alias().length()).reversed());
        List<String> out = new ArrayList<>();
        List<SourceAliasMatch> selected = new ArrayList<>();
        for (SourceAliasMatch match : matches) {
            boolean shadowedByLongerAlias = selected.stream().anyMatch(existing ->
                    !existing.source().equals(match.source())
                            && containsPhrase(existing.alias(), match.alias()));
            if (!shadowedByLongerAlias) {
                out.add(match.source());
                selected.add(match);
            }
        }
        if (stockIntent(folded)) {
            out.add("stocks");
        }
        return normalizeSources(out);
    }

    private record SourceAliasMatch(String source, String alias) {}

    private static boolean stockIntent(String foldedQuery) {
        if (foldedQuery == null || foldedQuery.isBlank()) {
            return false;
        }
        boolean marketWord = containsAny(foldedQuery, List.of("akcie", "stock", "stocks", "share", "shares", "equity", "ticker"));
        if (!marketWord) {
            return false;
        }
        return foldedQuery.matches(".*\\b[A-Za-z0-9^=.]{2,8}\\b.*");
    }

    private static List<String> geographiesFromQuery(String query) {
        String folded = CatalogTextUtils.foldAscii(query);
        List<String> out = new ArrayList<>();
        for (String code : CatalogCountryAliasRegistry.aliasesByCode().keySet()) {
            for (String alias : CatalogCountryAliasRegistry.aliasesFor(code)) {
                if (CatalogCountryAliasRegistry.matchAlias(folded, CatalogTextUtils.foldAscii(alias))) {
                    out.add(code);
                    break;
                }
            }
        }
        Map<String, Object> aggregateIntent = CatalogGeoIntent.detectGeoIntent(query);
        for (String code : CatalogGeoIntent.requestedGeoCodes(aggregateIntent)) {
            if (Set.of("EU", "EA", "EUROPE", "GLOBAL").contains(code)) {
                out.add("EA".equals(code) ? "U2" : code);
            }
        }
        return cleanList(out, 6);
    }

    private static List<String> normalizeGeographies(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String value : values) {
            String geo = normalizeGeo(value);
            if (!geo.isBlank()) {
                out.add(geo);
            }
        }
        return cleanList(out, 8);
    }

    private static String normalizeGeo(String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.isBlank()) {
            return "";
        }
        String upper = raw.toUpperCase(Locale.ROOT);
        if (Set.of("EU", "U2", "GLOBAL").contains(upper)) {
            return upper;
        }
        if ("EA".equals(upper)) {
            return "U2";
        }
        if (upper.length() == 3 && CatalogCountryIso3Registry.isKnownIso3(upper)) {
            return CatalogCountryIso3Registry.iso2For(upper);
        }
        if (CatalogCountryAliasRegistry.hasCode(upper)) {
            return upper;
        }
        String folded = CatalogTextUtils.foldAscii(raw);
        for (String code : CatalogCountryAliasRegistry.aliasesByCode().keySet()) {
            for (String alias : CatalogCountryAliasRegistry.aliasesFor(code)) {
                if (CatalogCountryAliasRegistry.matchAlias(folded, CatalogTextUtils.foldAscii(alias))) {
                    return code;
                }
            }
        }
        Map<String, Object> aggregateIntent = CatalogGeoIntent.detectGeoIntent(raw);
        List<String> aggregateCodes = CatalogGeoIntent.requestedGeoCodes(aggregateIntent);
        if (!aggregateCodes.isEmpty()) {
            String code = aggregateCodes.getFirst();
            return "EA".equals(code) ? "U2" : code;
        }
        return "";
    }

    private static List<String> readSelectedGeo(Map<String, Object> request) {
        List<String> geos = new ArrayList<>(readStringList(request.get("selected_geo")));
        String single = CatalogMapSupport.firstNonBlank(request, "geo", "country", "selected_country");
        if (!single.isBlank()) {
            geos.add(single);
        }
        return geos.stream().map(String::trim).filter(s -> !s.isBlank()).map(s -> s.toUpperCase(Locale.ROOT)).distinct().toList();
    }

    private static List<String> readStringList(Object raw) {
        if (raw instanceof Iterable<?> iterable) {
            List<String> out = new ArrayList<>();
            for (Object item : iterable) {
                String text = CatalogMapSupport.str(item);
                if (!text.isBlank()) {
                    out.add(text);
                }
            }
            return out;
        }
        String one = CatalogMapSupport.str(raw);
        if (one.isBlank()) {
            return List.of();
        }
        return List.of(one.split(",")).stream().map(String::trim).filter(s -> !s.isBlank()).toList();
    }

    private static List<String> normalizeSources(List<String> sources) {
        if (sources == null) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String source : sources) {
            String normalized = CatalogSourceRegistry.normalizeSearchSource(source);
            if (ALL_SEARCH_V2_SOURCES.contains(normalized)) {
                out.add(normalized);
            }
        }
        return new ArrayList<>(out);
    }

    private static List<String> readArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        node.forEach(item -> {
            String text = item.asText("").trim();
            if (!text.isBlank()) {
                out.add(text);
            }
        });
        return out;
    }

    private static List<String> cleanList(List<String> values, int limit) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .map(v -> v == null ? "" : v.trim())
                .filter(v -> !v.isBlank())
                .distinct()
                .limit(limit)
                .toList();
    }

    private static List<String> nonEmpty(List<String> value, List<String> fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    @SafeVarargs
    private static List<String> mergeStrings(List<String>... lists) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (List<String> list : lists) {
            for (String value : list == null ? List.<String>of() : list) {
                String text = value == null ? "" : value.trim();
                if (!text.isBlank()) {
                    out.add(text);
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.asText("").isBlank()) {
            return null;
        }
        return node.asText();
    }

    private static boolean parseBoolean(Object raw, boolean fallback) {
        if (raw == null) {
            return fallback;
        }
        String value = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (List.of("0", "false", "no", "off").contains(value)) {
            return false;
        }
        if (List.of("1", "true", "yes", "on").contains(value)) {
            return true;
        }
        return fallback;
    }

    private static boolean looksCzech(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.matches(".*[áčďéěíňóřšťúůýž].*") || containsAny(lower, List.of("cesk", "czech", "cr"));
    }

    private static boolean startsWithAny(String value, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix + " ") || value.equals(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String value, List<String> needles) {
        for (String needle : needles) {
            if (containsPhrase(value, needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsPhrase(String value, String phrase) {
        if (value == null || phrase == null || phrase.isBlank()) {
            return false;
        }
        return (" " + value + " ").contains(" " + phrase.trim() + " ");
    }

    private static String loadPrompt() {
        try (InputStream in = SearchV2QueryPlanner.class.getResourceAsStream("/search_v2/planner_prompt.md")) {
            if (in == null) {
                return "Return a valid SearchQueryPlan JSON object.";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return "Return a valid SearchQueryPlan JSON object.";
        }
    }

    /** Stable SHA-256 hex digest of the exact prompt text currently loaded. See {@link #PLANNER_PROMPT_VERSION}. */
    public static String promptContentHash() {
        return sha256Hex(PROMPT);
    }

    static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            return "";
        }
    }
}
