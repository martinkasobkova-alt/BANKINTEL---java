package cz.bankintel.explore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ExploreSectorContract {

    private ExploreSectorContract() {}

    public static Map<String, Object> buildContext(
            String sector,
            String question,
            Map<String, Object> geo,
            Map<String, Object> managerPreset,
            Map<String, Object> questionUnderstanding) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        String query = question != null && !question.isBlank() ? question.trim() : sector;
        ctx.put("sector", sector);
        ctx.put("query", query);
        ctx.put("manager_question", query);
        ctx.put("mode", geo.getOrDefault("mode", "none"));
        ctx.put("country_for_query", geoDisplayCountries(geo));
        ctx.put("geo", geo);
        ctx.put("manager_preset", managerPreset);
        ctx.put("question_understanding", questionUnderstanding);
        ctx.put("sector_ecosystem", buildSectorEcosystem(managerPreset));
        return ctx;
    }

    public static Map<String, Object> buildEmptyContract(
            Map<String, Object> ctx, String discoverySource, boolean partial) {
        Map<String, Object> geo = castMap(ctx.get("geo"));
        Map<String, Object> managerPreset = castMap(ctx.get("manager_preset"));
        Map<String, Object> sectorEcosystem = castMap(ctx.get("sector_ecosystem"));
        Map<String, Object> primarySector = castMap(sectorEcosystem.get("primary_sector"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("partial", partial);
        out.put("query", str(ctx.get("query")));
        out.put("sector", str(ctx.get("sector")));
        out.put("country", ctx.get("country_for_query"));
        out.put("geo_mode", str(ctx.get("mode")));
        out.put("continent", geo.get("continent_id"));
        out.put("geo_display", str(geo.get("display")));
        Map<String, Object> geoOut = new LinkedHashMap<>();
        geoOut.put("mode", str(geo.getOrDefault("mode", ctx.get("mode"))));
        geoOut.put("display", str(geo.get("display")));
        geoOut.put("country_codes", list(geo.get("country_codes")));
        // continent_id is null for geo_mode=country/countries/unknown (ExploreGeoResolver) — must stay null in JSON.
        geoOut.put("continent_id", geo.get("continent_id"));
        out.put("geo", geoOut);
        out.put("related_segments", ctx.get("related"));
        out.put("question_understanding", ctx.get("question_understanding"));
        out.put("search_query", str(ctx.get("query")));
        out.put("matched_manager_sector_id", primarySector.getOrDefault("sector_id", managerPreset.get("id")));
        out.put("matched_manager_sector_name_cs", primarySector.getOrDefault("sector_name_cs", managerPreset.get("label_cs")));
        out.put("matched_manager_sector_name_en", primarySector.getOrDefault("sector_name_en", null));
        out.put("dependent_sectors", List.of());
        out.put("dependent_sector_names_cs", List.of());
        out.put("discovery_source", discoverySource);
        out.put("discovery_fallback_reason", null);
        out.put("manager_intents", List.of());
        out.put("geo_scope", null);
        out.put("top_sources_used", List.of());
        out.put("sector_indicators", List.of());
        out.put("leading_indicators", List.of());
        out.put("macro_indicators", List.of());
        out.put("financial_indicators", List.of());
        out.put("cost_indicators", List.of());
        out.put("external_indicators", List.of());
        out.put("risk_indicators", List.of());
        out.put("forecast_indicators", List.of());
        out.put("web_sources", List.of());
        out.put("web_sources_total", 0);
        out.put("web_research_status", "not_attempted");
        out.put("recommended_chart_set", List.of());
        out.put("needs_filter_indicators", List.of());
        out.put("sector_ecosystem", sectorEcosystem.isEmpty() ? defaultSectorEcosystem() : sectorEcosystem);
        out.put("decision_context", emptyDecisionContext(ctx));
        out.put("outlook", emptyOutlook());
        out.put("analysis_score", emptyAnalysisScore());
        out.put("sector_indicators_total", 0);
        out.put("macro_indicators_total", 0);
        out.put("total_candidates", 0);
        out.put("index_hits", 0);
        out.put("deep_search_status", null);
        out.put("empty_hint", null);
        out.put("warnings", List.of());
        out.put("source_statuses", List.of());
        out.put("company_indicators", List.of());
        out.put("company_vs_market", List.of());
        out.put("user_data_used", false);
        out.put("user_uploads_used", List.of());
        return out;
    }

    public static Map<String, Object> mergeIndicators(
            Map<String, Object> base,
            List<Map<String, Object>> sectorIndicators,
            List<Map<String, Object>> macroIndicators,
            int totalCandidates,
            String deepSearchStatus,
            boolean partial) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        out.put("sector_indicators", sectorIndicators);
        out.put("macro_indicators", macroIndicators);
        out.put("sector_indicators_total", sectorIndicators.size());
        out.put("macro_indicators_total", macroIndicators.size());
        out.put("total_candidates", totalCandidates);
        out.put("partial", partial);
        out.put("deep_search_status", deepSearchStatus);
        out.put("recommended_chart_set", new ArrayList<>(sectorIndicators));
        if (sectorIndicators.isEmpty() && !partial) {
            out.put(
                    "empty_hint",
                    macroIndicators.isEmpty()
                            ? "Pro odvětví „" + out.get("sector") + "“ jsme v katalozích nenašli žádné ukazatele. "
                                    + "Zkuste přesnější název nebo použijte širší hledání."
                            : "Pro odvětví „" + out.get("sector") + "“ jsme nenašli žádné specifické ukazatele — "
                                    + "níže je jen obecný makroekonomický kontext (HDP, inflace, sazby apod.), "
                                    + "který nemusí přímo odpovídat na váš dotaz. Zkuste přesnější název nebo širší hledání.");
        }
        return out;
    }

    /**
     * Overlays the Manager Explorer web-research fallback result on top of an already-merged
     * contract. Kept as its own step (not folded into {@link #mergeIndicators}) because it is only
     * ever populated in the {@code catalogDiscoveryEmpty} branch of {@code ExploreSectorService} -
     * every other caller keeps {@link #buildEmptyContract}'s {@code web_sources=[]}/
     * {@code web_research_status="not_attempted"} defaults untouched. {@code webSources} must stay
     * out of {@code sectorIndicators}/{@code macroIndicators} - it is web context, not a catalog
     * indicator, and the report renders it as its own separate section.
     */
    public static Map<String, Object> mergeWebSources(
            Map<String, Object> base, List<Map<String, Object>> webSources, String webResearchStatus) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        out.put("web_sources", webSources);
        out.put("web_sources_total", webSources.size());
        out.put("web_research_status", webResearchStatus);
        return out;
    }

    private static Map<String, Object> buildSectorEcosystem(Map<String, Object> managerPreset) {
        Map<String, Object> ecosystem = new LinkedHashMap<>();
        ecosystem.put("analysis_mode", "single_sector");
        Map<String, Object> primary = new LinkedHashMap<>();
        primary.put("sector_id", managerPreset.get("id"));
        primary.put("sector_name_cs", managerPreset.get("label_cs"));
        primary.put("sector_name_en", managerPreset.get("sector_name_en"));
        primary.put("confidence", managerPreset.isEmpty() ? 0.0 : 0.75);
        ecosystem.put("primary_sector", primary);
        ecosystem.put("subsegment", null);
        ecosystem.put("linked_sectors", List.of());
        ecosystem.put("ecosystem_summary", "");
        ecosystem.put("critical_linkages", List.of());
        ecosystem.put("data_gaps", List.of());
        return ecosystem;
    }

    private static Map<String, Object> defaultSectorEcosystem() {
        Map<String, Object> ecosystem = new LinkedHashMap<>();
        ecosystem.put("analysis_mode", "single_sector");
        Map<String, Object> primarySector = new LinkedHashMap<>();
        primarySector.put("sector_id", null);
        primarySector.put("sector_name_cs", null);
        primarySector.put("sector_name_en", null);
        primarySector.put("confidence", 0.0);
        ecosystem.put("primary_sector", primarySector);
        ecosystem.put("subsegment", null);
        ecosystem.put("linked_sectors", List.of());
        ecosystem.put("ecosystem_summary", "");
        ecosystem.put("critical_linkages", List.of());
        ecosystem.put("data_gaps", List.of());
        return ecosystem;
    }

    private static Map<String, Object> emptyDecisionContext(Map<String, Object> ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("decision_signal", "insufficient_data");
        out.put("confidence", 0.0);
        out.put("summary", "");
        out.put("supports_expansion", List.of());
        out.put("argues_against_expansion", List.of());
        out.put("key_risks", List.of());
        out.put("missing_data", List.of());
        out.put("recommended_next_questions", List.of());
        out.put("question_understanding", ctx.get("question_understanding"));
        out.put(
                "ecosystem_interpretation",
                Map.of(
                        "primary_sector_evidence", List.of(),
                        "linked_sector_evidence", List.of(),
                        "demand_driver_evidence", List.of(),
                        "cost_driver_evidence", List.of(),
                        "supply_chain_risks", List.of(),
                        "financial_macro_risks", List.of(),
                        "summary", ""));
        return out;
    }

    private static Map<String, Object> emptyOutlook() {
        return Map.of(
                "historical_signal", "insufficient_data",
                "forecast_signal", "insufficient_data",
                "combined_signal", "insufficient_data",
                "forecast_sources_used", List.of());
    }

    /**
     * ETAPA 7 decision (keep as public contract, mark clearly as unsupported - option C):
     * {@code score_drivers}/{@code top_positive_for_question}/{@code top_negative_for_question}
     * are intentionally always empty. Populating them meaningfully would require a genuine
     * decision-driver-attribution analysis (which indicators pushed the verdict up/down and by
     * how much, with a direction and a score contribution - see the frontend's
     * {@code ExploreQuestionDrivers} component, which already expects exactly that shape) - a
     * real analytical feature, not a one-line fix, so it is deliberately out of scope here rather
     * than filled with fabricated numbers. The frontend already renders nothing when these are
     * empty (no crash, no misleading partial UI), so the contract is left as-is for whenever that
     * feature is actually built.
     */
    private static Map<String, Object> emptyAnalysisScore() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("composite", null);
        out.put("composite_confidence", 0.0);
        out.put("environment_score", null);
        out.put("environment_direction", "unknown");
        out.put("decision_score", null);
        out.put("decision_direction", "unknown");
        out.put("decision_answer", "insufficient_data");
        out.put("score_drivers", List.of());
        out.put("top_positive_for_question", List.of());
        out.put("top_negative_for_question", List.of());
        out.put("missing_or_weak_evidence", List.of());
        out.put("data_limitations", List.of());
        return out;
    }

    private static String geoDisplayCountries(Map<String, Object> geo) {
        Object display = geo.get("display");
        return display == null ? null : String.valueOf(display);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        return List.of();
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
