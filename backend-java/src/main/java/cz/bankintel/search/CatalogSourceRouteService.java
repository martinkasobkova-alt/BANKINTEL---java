package cz.bankintel.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class CatalogSourceRouteService {

    private static final int ROUTE_CANDIDATE_POOL_SIZE = 8;
    private static final List<String> SOURCE_ROUTE_DEFAULT_POOL =
            List.of("arad", "csu", "eurostat", "ecb2", "fred", "imf", "data360", "bis", "oecd4", "commodities");

    private final CatalogQueryPlanner queryPlanner;

    public Map<String, Object> routeSources(Map<String, Object> payload) {
        String query = firstNonBlank(payload, "q", "query");
        if (query.length() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dotaz musí mít alespoň 2 znaky.");
        }
        List<String> allowed = readSources(payload);
        int maxSelected = parseMaxSources(payload.get("max_sources"));
        Map<String, Object> plan = queryPlanner.plan(query, allowed.isEmpty() ? null : allowed);
        @SuppressWarnings("unchecked")
        List<String> plannerSources = new ArrayList<>((List<String>) plan.getOrDefault("sources", List.of()));
        List<String> candidates = buildCandidatePool(query, allowed, plannerSources);
        boolean trustedPlannerRoute = trustedPlannerRoute(plan, plannerSources);
        CatalogSourceRouter.RouteResult routed = trustedPlannerRoute
                ? null
                : CatalogSourceRouter.routeRelevantSources(query, candidates, plannerSources);
        List<String> sources = trustedPlannerRoute ? new ArrayList<>(plannerSources) : new ArrayList<>(routed.sources());
        if (sources.size() > maxSelected) {
            sources = sources.subList(0, maxSelected);
        }
        Map<String, Object> route = new LinkedHashMap<>(plan);
        route.put("sources", sources);
        route.put("selected_sources", sources);
        route.put("source_router", trustedPlannerRoute
                ? Map.of("mode", "trusted_search_v2_route", "reason", "Planner route already came from concept/source capability registry.")
                : routed.diagnostics());
        route.put("source_candidate_pool", candidates);
        return Map.of("ok", true, "query", query, "source_route", route);
    }

    @SuppressWarnings("unchecked")
    private static boolean trustedPlannerRoute(Map<String, Object> plan, List<String> plannerSources) {
        if (plannerSources == null || plannerSources.isEmpty()) {
            return false;
        }
        String planner = String.valueOf(plan.getOrDefault("planner", "")).trim();
        if (!"openai".equalsIgnoreCase(planner) && !planner.startsWith("exact_entity")) {
            return false;
        }
        Object profileRaw = plan.get("semantic_profile");
        if (!(profileRaw instanceof Map<?, ?> profile)) {
            return false;
        }
        Object routingRaw = profile.get("source_routing");
        if (!(routingRaw instanceof Map<?, ?> routing)) {
            return false;
        }
        Object preferredRaw = routing.get("preferred_sources");
        return preferredRaw instanceof List<?> preferred && !preferred.isEmpty();
    }

    private static int parseMaxSources(Object raw) {
        if (raw == null) {
            return 5;
        }
        try {
            return Math.max(1, Math.min(Integer.parseInt(String.valueOf(raw)), 5));
        } catch (NumberFormatException ex) {
            return 5;
        }
    }

    private static List<String> readSources(Map<String, Object> payload) {
        Object raw = payload.get("sources");
        if (raw == null) {
            raw = payload.get("catalog_sources");
        }
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(v -> CatalogSourceRegistry.normalizeSearchSource(String.valueOf(v)))
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
    }

    private static List<String> buildCandidatePool(String query, List<String> allowed, List<String> plannerSources) {
        List<String> universe = allowed == null || allowed.isEmpty() ? SOURCE_ROUTE_DEFAULT_POOL : allowed;
        List<String> normalizedUniverse = universe.stream()
                .map(CatalogSourceRegistry::normalizeSearchSource)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
        java.util.LinkedHashSet<String> selected = new java.util.LinkedHashSet<>();
        for (String source : CatalogLikelySources.inferRuleMatchedCatalogSources(query)) {
            addIfAllowed(selected, normalizedUniverse, source);
            if (selected.size() >= ROUTE_CANDIDATE_POOL_SIZE) {
                break;
            }
        }
        for (String source : CatalogLikelySources.inferLikelyCatalogSources(query)) {
            addIfAllowed(selected, normalizedUniverse, source);
            if (selected.size() >= ROUTE_CANDIDATE_POOL_SIZE) {
                break;
            }
        }
        for (String source : plannerSources == null ? List.<String>of() : plannerSources) {
            addIfAllowed(selected, normalizedUniverse, source);
        }
        if (selected.size() < 2) {
            for (String source : normalizedUniverse) {
                selected.add(source);
                if (selected.size() >= Math.min(ROUTE_CANDIDATE_POOL_SIZE, normalizedUniverse.size())) {
                    break;
                }
            }
        }
        return new ArrayList<>(selected);
    }

    private static void addIfAllowed(java.util.LinkedHashSet<String> selected, List<String> allowed, String source) {
        String normalized = CatalogSourceRegistry.normalizeSearchSource(source);
        if (!normalized.isBlank() && allowed.contains(normalized)) {
            selected.add(normalized);
        }
    }

    private static String firstNonBlank(Map<String, Object> payload, String... keys) {
        for (String key : keys) {
            Object value = payload.get(key);
            if (value != null) {
                String text = String.valueOf(value).trim();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }
}
