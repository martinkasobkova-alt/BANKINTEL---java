package cz.bankintel.search;

import cz.bankintel.util.BankIntelEnvVars;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Source router for deep search — simplified port of
 * {@code Bankoapp-main/backend/services/catalog_source_router.py}.
 *
 * <p>Union of heuristic {@link CatalogLikelySources} + planner sources.
 * Gated by env {@code CATALOG_DEEP_SEARCH_SOURCE_ROUTER}.
 */
public final class CatalogSourceRouter {

    private CatalogSourceRouter() {}

    public static boolean routerEnabled() {
        return BankIntelEnvVars.isTruthy("CATALOG_DEEP_SEARCH_SOURCE_ROUTER");
    }

    /**
     * Route relevant catalog sources — port of {@code route_relevant_sources} (heuristic variant).
     *
     * @param query user query
     * @param allowedSources candidate source ids (planner output or allow-list)
     * @param plannerSources optional explicit planner picks to union with heuristics
     */
    public static RouteResult routeRelevantSources(
            String query, List<String> allowedSources, List<String> plannerSources) {
        List<String> candidates = normalizeList(allowedSources);
        if (candidates.isEmpty()) {
            candidates = CatalogLikelySources.inferLikelyCatalogSources(query);
        }
        Map<String, Object> diag = new LinkedHashMap<>();
        diag.put("candidates", candidates);
        diag.put("router_enabled", routerEnabled());

        if (!routerEnabled()) {
            diag.put("reason", "router_disabled");
            diag.put("applied", false);
            return new RouteResult(candidates, diag);
        }

        if (candidates.size() <= 3) {
            diag.put("reason", "small_candidate_set");
            diag.put("applied", false);
            return new RouteResult(candidates, diag);
        }

        List<String> likely = CatalogLikelySources.inferLikelyCatalogSources(query);
        List<String> intentSources = routeByIntentTags(query, likely);
        Set<String> relevant = new LinkedHashSet<>();
        for (String s : likely) {
            if (candidates.contains(s)) {
                relevant.add(s);
            }
        }
        for (String s : intentSources) {
            if (candidates.contains(s)) {
                relevant.add(s);
            }
        }
        if (plannerSources != null) {
            for (String s : plannerSources) {
                String norm = CatalogSourceRegistry.normalizeSearchSource(s);
                if (!norm.isBlank() && candidates.contains(norm)) {
                    relevant.add(norm);
                }
            }
        }

        Map<String, Object> geo = CatalogGeoIntent.detectGeoIntent(query);
        relevant.removeIf(s -> !CatalogGeoIntent.isCatalogSourceGeoApplicable(s, geo));

        diag.put("likely_sources", likely);
        diag.put("intent_sources", intentSources);
        diag.put("profile_hits", intersectSorted(likely, candidates));

        if (relevant.size() < 2) {
            diag.put("reason", "low_confidence_fallback_all");
            diag.put("applied", false);
            return new RouteResult(candidates, diag);
        }

        List<String> selected = new ArrayList<>();
        for (String c : candidates) {
            if (relevant.contains(c)) {
                selected.add(c);
            }
        }
        diag.put("applied", true);
        diag.put("selected", selected);
        diag.put("dropped", candidates.stream().filter(c -> !relevant.contains(c)).toList());
        return new RouteResult(selected, diag);
    }

    public static RouteResult routeRelevantSources(String query, List<String> allowedSources) {
        return routeRelevantSources(query, allowedSources, null);
    }

    /** Union heuristic likely sources with planner sources while preserving order. */
    public static List<String> unionHeuristicAndPlanner(
            String query, List<String> plannerSources, List<String> allowedSources) {
        List<String> base = allowedSources == null || allowedSources.isEmpty()
                ? CatalogLikelySources.inferLikelyCatalogSources(query)
                : normalizeList(allowedSources);
        RouteResult routed = routeRelevantSources(query, base, plannerSources);
        if (routed.diagnostics().get("applied") == Boolean.TRUE) {
            return routed.sources();
        }
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        for (String s : CatalogLikelySources.inferLikelyCatalogSources(query)) {
            if (base.contains(s)) {
                merged.add(s);
            }
        }
        for (String s : routeByIntentTags(query, List.of())) {
            if (base.contains(s)) {
                merged.add(s);
            }
        }
        if (plannerSources != null) {
            for (String s : plannerSources) {
                String norm = CatalogSourceRegistry.normalizeSearchSource(s);
                if (!norm.isBlank() && base.contains(norm)) {
                    merged.add(norm);
                }
            }
        }
        if (merged.size() < 2) {
            return base;
        }
        List<String> out = new ArrayList<>();
        for (String c : base) {
            if (merged.contains(c)) {
                out.add(c);
            }
        }
        return out.isEmpty() ? base : out;
    }

    private static List<String> normalizeList(List<String> sources) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (sources == null) {
            return out;
        }
        for (String raw : sources) {
            String s = CatalogSourceRegistry.normalizeSearchSource(raw);
            if (!s.isBlank() && seen.add(s)) {
                out.add(s);
            }
        }
        return out;
    }

    private static List<String> intersectSorted(List<String> a, List<String> b) {
        Set<String> bs = new LinkedHashSet<>(b);
        return a.stream().filter(bs::contains).toList();
    }

    /** Intent-tag routing — sidecar tags map to preferred catalogs. */
    static List<String> routeByIntentTags(String query, List<String> fallback) {
        List<String> tags = CatalogSearchMetadataSidecar.deriveQueryIntentTags(query);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (tags.contains("energy")) {
            out.addAll(List.of("fred", "data360", "imf", "commodities"));
        }
        if (tags.contains("inflation")) {
            out.addAll(List.of("csu", "arad", "eurostat", "fred", "imf"));
        }
        if (tags.contains("gdp")) {
            out.addAll(List.of("imf", "eurostat", "data360", "fred", "oecd4"));
        }
        if (tags.contains("lending") || tags.contains("banking") || tags.contains("profitability")) {
            out.addAll(List.of("arad", "ecb2", "ecb", "bis", "eurostat"));
        }
        if (tags.contains("production") || tags.contains("retail")) {
            out.addAll(List.of("csu", "eurostat", "fred", "imf"));
        }
        if (out.isEmpty()) {
            return fallback == null ? List.of() : fallback;
        }
        return new ArrayList<>(out);
    }

    public record RouteResult(List<String> sources, Map<String, Object> diagnostics) {}
}
