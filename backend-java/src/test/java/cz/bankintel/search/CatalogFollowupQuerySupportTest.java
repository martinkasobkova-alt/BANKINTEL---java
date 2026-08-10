package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CatalogFollowupQuerySupportTest {

    @Test
    void conversationalSourceFollowupKeepsCurrentDataFocus() {
        Map<String, Object> context = Map.of(
                "root_query", "spotreba vody v municipalitach",
                "current_focus_query", "spotreba vody v municipalitach",
                "found_series_summary", List.of(Map.of("title", "Municipal water consumption")));

        String effective = CatalogFollowupQuerySupport.buildContextualFollowupQuery(
                "zkus to ve World Bank Data360", context);

        assertEquals("spotreba vody v municipalitach", effective);
        assertTrue(CatalogFollowupQuerySupport.requestedSourcesFromMessage("zkus to ve World Bank Data360")
                .contains("data360"));
    }

    @Test
    void alternativeSourceRequestPreservesTopicAndExcludesNamedSource() {
        Map<String, Object> context = Map.of(
                "root_query", "zisk bank",
                "current_focus_query", "zisk bank",
                "sources", List.of("ecb2"),
                "found_series_summary", List.of(Map.of("title", "ROA eurozony")));

        CatalogFollowupQuerySupport.SourceRequest request =
                CatalogFollowupQuerySupport.sourceRequest("no ale co jiny zdroj mimo ecb?");

        assertTrue(request.alternativesRequested());
        assertTrue(request.excludedSources().contains("ecb2"));
        assertTrue(request.includedSources().isEmpty());
        assertTrue(CatalogFollowupQuerySupport.isSearchRefinementRequest("no ale co jiny zdroj mimo ecb?"));
        assertEquals(
                "zisk bank",
                CatalogFollowupQuerySupport.buildContextualFollowupQuery(
                        "no ale co jiny zdroj mimo ecb?", context));
    }

    @Test
    void newNonMacroTopicDoesNotInheritOldBankingContext() {
        Map<String, Object> context = Map.of(
                "root_query", "rentabilita bank",
                "current_focus_query", "rentabilita bank",
                "topic_anchor", "ROE ROA ziskovost bank",
                "found_series_summary", List.of(Map.of("title", "Return on equity of banks")));

        String effective =
                CatalogFollowupQuerySupport.buildContextualFollowupQuery("najdi data o vzdelani ve Francii", context);

        assertEquals("najdi data o vzdelani ve Francii", effective);
        assertFalse(effective.contains("rentabilita bank"));
    }

    @Test
    void topicAnchorForOpenDataTopicsDoesNotInjectGdp() {
        String anchor = CatalogFollowupQuerySupport.topicAnchorFromFoundSummary(List.of(
                Map.of("title", "Educational attainment by region"),
                Map.of("title", "Population by municipality")));

        assertTrue(anchor.contains("vzdelavani"));
        assertTrue(anchor.contains("populace"));
        assertFalse(anchor.contains("HDP"));
        assertFalse(anchor.contains("GDP"));
    }
}
