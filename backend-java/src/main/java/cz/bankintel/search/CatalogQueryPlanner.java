package cz.bankintel.search;

import com.fasterxml.jackson.databind.JsonNode;
import cz.bankintel.search.model.CatalogKeys;
import cz.bankintel.search.model.GeoIntentSnapshot;
import cz.bankintel.search.model.SearchPlan;
import cz.bankintel.search.openai.OpenAiClient;
import cz.bankintel.search.v2.planner.SearchV2QueryPlanner;
import cz.bankintel.search.v2.schema.SearchQueryPlan;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CatalogQueryPlanner {

    private static final Logger log = LoggerFactory.getLogger(CatalogQueryPlanner.class);
    private static final int MAX_PLANNED_SEARCH_TERMS = 10;
    private static final int DETERMINISTIC_TERM_HEADROOM = 3;
    private static final int MAX_ROUTING_BASE_SOURCES = 5;

    private static final String SYSTEM_PROMPT =
            """
            You plan catalog searches over macro/finance data sources.
            Respond with JSON only:
            {"sources":["fred","eurostat"],"search_terms":["unemployment rate"],"topic":"labour market","country_hint":""}
            Allowed sources: arad, csu, eurostat, ecb2, fred, imf, data360, bis, oecd4.
            Pick 1-4 most relevant sources. search_terms: 2-5 concise English phrases.
            If the question is analytical or forward-looking (e.g. "what will electricity prices be", "what drives inflation"),
            include search_terms for the KEY DRIVER indicators too, not just the headline metric — e.g. for electricity
            prices also add wholesale gas price, CO2 / carbon allowance price, electricity demand, power generation.
            This lets the app assemble the drivers into a data-grounded story. Keep every term a real economic indicator name.
    """;

    private final OpenAiClient openAiClient;
    private final SearchV2QueryPlanner searchV2QueryPlanner;

    /** API boundary — returns legacy map shape for JSON compatibility. */
    public Map<String, Object> plan(String query, List<String> requestedSources) {
        return planTyped(query, requestedSources).toMap();
    }

    public SearchPlan planTyped(String query, List<String> requestedSources) {
        return planTyped(query, requestedSources, true);
    }

    public SearchPlan planTyped(String query, List<String> requestedSources, boolean useAi) {
        SearchPlan fallback = localPlan(query, requestedSources);
        if (!useAi) {
            return fallback;
        }
        try {
            SearchQueryPlan v2Plan = searchV2QueryPlanner.plan(v2PlannerRequest(query, requestedSources));
            return fromSearchV2Plan(query, requestedSources, fallback, v2Plan);
        } catch (Exception ex) {
            log.warn("Search V2 query planning failed for legacy route, using local plan: {}", ex.getMessage());
            return fallback;
        }
    }

    private static Map<String, Object> v2PlannerRequest(String query, List<String> requestedSources) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(CatalogKeys.Q, query);
        payload.put(CatalogKeys.QUERY, query);
        payload.put("use_ai", true);
        payload.put("no_cache", true);
        if (requestedSources != null && !requestedSources.isEmpty()) {
            payload.put(CatalogKeys.SOURCES, requestedSources);
        }
        return payload;
    }

    private static SearchPlan fromSearchV2Plan(
            String query, List<String> requestedSources, SearchPlan fallback, SearchQueryPlan v2Plan) {
        List<String> sources = searchV2Sources(v2Plan, fallback.sources());
        if (requestedSources != null && !requestedSources.isEmpty()) {
            sources = sources.stream().filter(requestedSources::contains).toList();
            if (sources.isEmpty()) {
                sources = requestedSources;
            }
        }
        List<String> terms = mergeStableAndSupplementalSearchTerms(
                fallback.searchTerms(), v2Plan.firstPassSearchTerms());
        List<String> probeTerms = mergeStableAndSupplementalSearchTerms(
                fallback.indexProbeTerms(), v2Plan.allSearchTerms());
        GeoIntentSnapshot geo = v2Plan.geographies() == null || v2Plan.geographies().isEmpty()
                ? fallback.geoIntent()
                : GeoIntentSnapshot.fromDetection(query);
        String countryHint = v2Plan.geographies() == null || v2Plan.geographies().isEmpty()
                ? fallback.countryHint()
                : v2Plan.geographies().getFirst();
        Map<String, Object> semanticProfile = semanticProfileFromSearchV2(query, fallback, v2Plan, terms, sources);
        String topic = String.join(" / ", v2Plan.primaryConcepts() == null ? List.<String>of() : v2Plan.primaryConcepts());
        if (topic.isBlank()) {
            topic = fallback.topic();
        }
        String plannerStatus = v2Plan.plannerStatus() == null || v2Plan.plannerStatus().isBlank()
                ? "search_v2"
                : v2Plan.plannerStatus();
        return new SearchPlan(
                sources,
                terms,
                sources,
                geo,
                topic,
                countryHint,
                plannerStatus.startsWith("openai") ? "openai" : plannerStatus,
                probeTerms,
                semanticProfile);
    }

    private static List<String> searchV2Sources(SearchQueryPlan plan, List<String> fallbackSources) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String source : plan.explicitSources() == null ? List.<String>of() : plan.explicitSources()) {
            addNormalizedSource(out, source);
        }
        if (!out.isEmpty()) {
            return new ArrayList<>(out);
        }
        if (plan.sourceRouting() != null && plan.sourceRouting().preferredSources() != null) {
            for (String source : plan.sourceRouting().preferredSources()) {
                addNormalizedSource(out, source);
            }
        }
        if (out.isEmpty()) {
            for (String source : fallbackSources == null ? List.<String>of() : fallbackSources) {
                addNormalizedSource(out, source);
            }
        }
        return new ArrayList<>(out);
    }

    private static void addNormalizedSource(LinkedHashSet<String> out, String source) {
        String normalized = CatalogSourceRegistry.normalizeSearchSource(source);
        if (!normalized.isBlank()) {
            out.add(normalized);
        }
    }

    private static Map<String, Object> semanticProfileFromSearchV2(
            String query, SearchPlan fallback, SearchQueryPlan v2Plan, List<String> terms, List<String> sources) {
        Map<String, Object> out = new LinkedHashMap<>(CatalogQuerySemanticProfile.build(
                query,
                fallback.geoIntent(),
                terms,
                fallback.indexProbeTerms(),
                sources));
        List<String> primaryConcepts = v2Plan.primaryConcepts() == null ? List.of() : v2Plan.primaryConcepts();
        List<String> supportingConcepts = v2Plan.supportingConcepts() == null ? List.of() : v2Plan.supportingConcepts();
        out.put(CatalogKeys.TOPIC, primaryConcepts.isEmpty() ? fallback.topic() : String.join(" / ", primaryConcepts));
        out.put(CatalogKeys.ENGLISH_QUERY, firstDifferentTerm(query, terms));
        out.put(CatalogKeys.QUERY_VARIANTS, v2Plan.queryVariants() == null
                ? terms
                : v2Plan.queryVariants().stream().map(variant -> variant.toMap()).toList());
        out.put(CatalogKeys.METRIC_TERMS, termRows(primaryConcepts));
        out.put(CatalogKeys.DOMAIN_TERMS, termRows(supportingConcepts));
        out.put(CatalogKeys.PRIMARY_CONCEPTS, primaryConcepts);
        out.put(
                CatalogKeys.INSTITUTIONAL_SECTORS,
                v2Plan.institutionalSectors() == null ? List.of() : v2Plan.institutionalSectors());
        out.put(
                CatalogKeys.METRIC_INTENTS,
                v2Plan.metricIntents() == null ? List.of() : v2Plan.metricIntents());
        out.put(
                CatalogKeys.REQUIRED_GEO_CODES,
                v2Plan.geographies() == null ? List.of() : v2Plan.geographies());
        out.put(CatalogKeys.INDICATORS, primaryConcepts.isEmpty() ? terms : primaryConcepts);
        out.put(CatalogKeys.QUERY_SHAPE, queryShape(primaryConcepts, v2Plan.geographies()));
        out.put("confidence", v2Plan.plannerStatus() != null && v2Plan.plannerStatus().startsWith("openai") ? 0.92 : 0.78);
        out.put("planner_status", v2Plan.plannerStatus());
        out.put("source_routing", v2Plan.sourceRouting() == null ? Map.of() : v2Plan.sourceRouting().toMap());
        out.put("llm_planner", v2Plan.llmPlannerTrace() == null ? Map.of() : v2Plan.llmPlannerTrace());
        out.put("fallback_trace", v2Plan.fallbackTrace() == null ? Map.of() : v2Plan.fallbackTrace());
        return out;
    }

    private static List<Map<String, Object>> termRows(List<String> labels) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String label : labels == null ? List.<String>of() : labels) {
            if (label != null && !label.isBlank()) {
                out.add(Map.of("label", label.trim(), "surfaces", List.of(label.trim())));
            }
        }
        return out;
    }

    private static String queryShape(List<String> primaryConcepts, List<String> geos) {
        boolean concept = primaryConcepts != null && !primaryConcepts.isEmpty();
        boolean geo = geos != null && !geos.isEmpty();
        if (concept && geo) {
            return "metric_geo";
        }
        return concept ? "metric" : geo ? "open_topic_geo" : "open_topic";
    }

    private static String firstDifferentTerm(String query, List<String> terms) {
        String q = CatalogTextUtils.foldAscii(query == null ? "" : query);
        for (String term : terms == null ? List.<String>of() : terms) {
            if (!CatalogTextUtils.foldAscii(term).equals(q)) {
                return term;
            }
        }
        return "";
    }

    /**
     * Keeps a stable query-derived prefix and lets the LLM add bounded recall variants behind it.
     * Repeated planner paraphrases therefore cannot evict the terms that are identical for the same
     * user query, while useful translations and professional synonyms still reach retrieval.
     */
    private static List<String> mergeStableAndSupplementalSearchTerms(
            List<String> stableTerms, List<String> supplementalTerms) {
        List<String> out = new ArrayList<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        int stableLimit = Math.min(
                MAX_PLANNED_SEARCH_TERMS - DETERMINISTIC_TERM_HEADROOM,
                stableTerms == null ? 0 : stableTerms.size());
        for (String term : stableTerms == null ? List.<String>of() : stableTerms) {
            addSearchTerm(out, seen, term);
            if (out.size() >= stableLimit) {
                break;
            }
        }
        for (String term : supplementalTerms == null ? List.<String>of() : supplementalTerms) {
            addSearchTerm(out, seen, term);
        }
        for (String term : stableTerms == null ? List.<String>of() : stableTerms) {
            addSearchTerm(out, seen, term);
        }
        return out.size() > MAX_PLANNED_SEARCH_TERMS ? out.subList(0, MAX_PLANNED_SEARCH_TERMS) : out;
    }

    @Deprecated
    private SearchPlan legacyOpenAiPlan(String query, List<String> requestedSources, SearchPlan fallback) {
        if (!openAiClient.isConfigured()) {
            return fallback;
        }
        try {
            JsonNode parsed = openAiClient.plannerCompletionJson(SYSTEM_PROMPT, "User query: " + query);
            List<String> sources = readSourceList(parsed.get(CatalogKeys.SOURCES));
            if (sources.isEmpty()) {
                sources = fallback.sources();
            }
            if (requestedSources != null && !requestedSources.isEmpty()) {
                sources = sources.stream().filter(requestedSources::contains).toList();
                if (sources.isEmpty()) {
                    sources = requestedSources;
                }
            }
            List<String> terms = mergeSearchTerms(
                    readSearchTermList(parsed.get(CatalogKeys.SEARCH_TERMS)), fallback.searchTerms());
            if (terms.isEmpty()) {
                terms = fallback.searchTerms();
            }
            Map<String, Object> semanticProfile = CatalogQuerySemanticProfile.build(
                    query, fallback.geoIntent(), terms, fallback.indexProbeTerms(), fallback.likelySources());
            String plannerTopic = parsed.path(CatalogKeys.TOPIC).asText("");
            if (!plannerTopic.isBlank()) {
                semanticProfile.put("planner_topic", plannerTopic);
            }
            SearchPlan openAiPlan = new SearchPlan(
                    sources,
                    terms,
                    fallback.likelySources(),
                    fallback.geoIntent(),
                    semanticTopic(semanticProfile, plannerTopic),
                    parsed.path(CatalogKeys.COUNTRY_HINT).asText(""),
                    "openai",
                    fallback.indexProbeTerms(),
                    semanticProfile);
            return applySourceRouting(query, openAiPlan, requestedSources);
        } catch (Exception ex) {
            log.warn("OpenAI query planning failed, using local plan: {}", ex.getMessage());
            return fallback;
        }
    }

    private SearchPlan applySourceRouting(String query, SearchPlan plan, List<String> requestedSources) {
        List<String> allowed = requestedSources == null || requestedSources.isEmpty()
                ? CatalogLikelySources.DEFAULT_SOURCES_ORDER
                : requestedSources.stream()
                        .map(CatalogSourceRegistry::normalizeSearchSource)
                        .filter(s -> !s.isBlank())
                        .distinct()
                        .toList();
        List<String> routingBase = buildRoutingBase(query, plan, allowed);
        List<String> routed = CatalogSourceRouter.unionHeuristicAndPlanner(query, plan.sources(), routingBase);
        routed = promoteRuleMatchedSources(query, routed, allowed);
        if (requestedSources != null && !requestedSources.isEmpty()) {
            routed = routed.stream().filter(requestedSources::contains).toList();
            if (routed.isEmpty()) {
                routed = plan.sources();
            }
        }
        List<String> likely = CatalogLikelySources.inferLikelyCatalogSources(query);
        GeoIntentSnapshot geo = plan.geoIntent() != null && !plan.geoIntent().isEmpty()
                ? plan.geoIntent()
                : GeoIntentSnapshot.fromDetection(query);
        return new SearchPlan(
                routed,
                plan.searchTerms(),
                likely,
                geo,
                plan.topic(),
                plan.countryHint(),
                plan.planner(),
                plan.indexProbeTerms().isEmpty()
                        ? CatalogSearchLexicon.buildIndexProbeTerms(query, 6)
                        : plan.indexProbeTerms(),
                plan.semanticProfile());
    }

    private static List<String> buildRoutingBase(String query, SearchPlan plan, List<String> allowedSources) {
        List<String> allowed = allowedSources == null || allowedSources.isEmpty()
                ? CatalogLikelySources.DEFAULT_SOURCES_ORDER
                : allowedSources;
        LinkedHashMap<String, Boolean> selected = new LinkedHashMap<>();
        for (String source : CatalogLikelySources.inferRuleMatchedCatalogSources(query)) {
            addSourceIfAllowed(selected, allowed, source);
            if (selected.size() >= MAX_ROUTING_BASE_SOURCES) {
                return new ArrayList<>(selected.keySet());
            }
        }
        List<String> likely = CatalogLikelySources.inferLikelyCatalogSources(query, plan.searchTerms());
        for (String source : likely) {
            addSourceIfAllowed(selected, allowed, source);
            if (selected.size() >= MAX_ROUTING_BASE_SOURCES) {
                return new ArrayList<>(selected.keySet());
            }
        }
        for (String source : plan.sources()) {
            addSourceIfAllowed(selected, allowed, source);
            if (selected.size() >= MAX_ROUTING_BASE_SOURCES) {
                return new ArrayList<>(selected.keySet());
            }
        }
        if (selected.size() < 2) {
            for (String source : allowed) {
                addSourceIfAllowed(selected, allowed, source);
                if (selected.size() >= Math.min(MAX_ROUTING_BASE_SOURCES, allowed.size())) {
                    break;
                }
            }
        }
        return new ArrayList<>(selected.keySet());
    }

    private static List<String> promoteRuleMatchedSources(String query, List<String> routed, List<String> allowedSources) {
        List<String> matched = CatalogLikelySources.inferRuleMatchedCatalogSources(query);
        if (matched.isEmpty()) {
            return routed;
        }
        List<String> allowed = allowedSources == null || allowedSources.isEmpty()
                ? CatalogLikelySources.DEFAULT_SOURCES_ORDER
                : allowedSources;
        LinkedHashMap<String, Boolean> selected = new LinkedHashMap<>();
        for (String source : matched) {
            addSourceIfAllowed(selected, allowed, source);
            if (selected.size() >= MAX_ROUTING_BASE_SOURCES) {
                return new ArrayList<>(selected.keySet());
            }
        }
        for (String source : routed == null ? List.<String>of() : routed) {
            addSourceIfAllowed(selected, allowed, source);
            if (selected.size() >= MAX_ROUTING_BASE_SOURCES) {
                break;
            }
        }
        if (selected.size() < 2) {
            return routed;
        }
        return new ArrayList<>(selected.keySet());
    }

    private static void addSourceIfAllowed(Map<String, Boolean> selected, List<String> allowed, String source) {
        String normalized = CatalogSourceRegistry.normalizeSearchSource(source);
        if (!normalized.isBlank() && allowed.contains(normalized)) {
            selected.putIfAbsent(normalized, true);
        }
    }

    private SearchPlan localPlan(String query, List<String> requestedSources) {
        List<String> allowed = requestedSources == null || requestedSources.isEmpty()
                ? CatalogLikelySources.DEFAULT_SOURCES_ORDER
                : requestedSources.stream()
                        .map(CatalogSourceRegistry::normalizeSearchSource)
                        .filter(s -> !s.isBlank())
                        .distinct()
                        .toList();
        List<String> likely = CatalogLikelySources.inferLikelyCatalogSources(query);
        List<String> sources = new ArrayList<>();
        for (String src : likely) {
            if (allowed.contains(src)) {
                sources.add(src);
            }
            if (sources.size() >= 5) {
                break;
            }
        }
        if (sources.isEmpty()) {
            sources.addAll(allowed.stream().limit(3).toList());
        }
        sources = CatalogSourceRouter.unionHeuristicAndPlanner(query, sources, sources);

        List<String> terms = CatalogSearchSynonyms.expandSearchQueries(query).stream().distinct().limit(6).toList();
        List<String> probeTerms = CatalogSearchLexicon.buildIndexProbeTerms(query, 6);
        GeoIntentSnapshot geo = GeoIntentSnapshot.fromDetection(query);
        String countryHint = geo.countryCode();
        Map<String, Object> semanticProfile = CatalogQuerySemanticProfile.build(query, geo, terms, probeTerms, likely);
        String topic = String.valueOf(semanticProfile.getOrDefault(CatalogKeys.TOPIC, ""));

        return new SearchPlan(sources, terms, likely, geo, topic, countryHint, "local", probeTerms, semanticProfile);
    }

    private static String semanticTopic(Map<String, Object> semanticProfile, String plannerTopic) {
        String topic = String.valueOf(semanticProfile.getOrDefault(CatalogKeys.TOPIC, "")).trim();
        if (!topic.isBlank()) {
            return topic;
        }
        return plannerTopic == null ? "" : plannerTopic.trim();
    }

    private static List<String> readSourceList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        node.forEach(item -> {
            String text = item.asText("").trim().toLowerCase(Locale.ROOT);
            if (!text.isBlank()) {
                out.add(CatalogSourceRegistry.normalizeSearchSource(text));
            }
        });
        return out.stream().distinct().toList();
    }

    private static List<String> readSearchTermList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        node.forEach(item -> {
            String text = item.asText("").trim().toLowerCase(Locale.ROOT);
            if (!text.isBlank()) {
                out.add(text);
            }
        });
        return out.stream().distinct().toList();
    }

    static List<String> mergeSearchTerms(List<String> modelTerms, List<String> fallbackTerms) {
        List<String> out = new ArrayList<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();

        int deterministicAdded = 0;
        for (String term : fallbackTerms == null ? List.<String>of() : fallbackTerms) {
            if (deterministicAdded >= DETERMINISTIC_TERM_HEADROOM) {
                break;
            }
            if (addSearchTerm(out, seen, term)) {
                deterministicAdded++;
            }
        }
        for (String term : modelTerms == null ? List.<String>of() : modelTerms) {
            addSearchTerm(out, seen, term);
        }
        for (String term : fallbackTerms == null ? List.<String>of() : fallbackTerms) {
            addSearchTerm(out, seen, term);
        }
        return out.size() > MAX_PLANNED_SEARCH_TERMS ? out.subList(0, MAX_PLANNED_SEARCH_TERMS) : out;
    }

    private static boolean addSearchTerm(List<String> out, Map<String, Boolean> seen, String term) {
        if (term == null) {
            return false;
        }
        String trimmed = term.trim();
        if (trimmed.length() < 2) {
            return false;
        }
        String key = CatalogTextUtils.foldAscii(trimmed)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
        if (key.isBlank() || seen.containsKey(key)) {
            return false;
        }
        seen.put(key, true);
        out.add(trimmed);
        return true;
    }
}
