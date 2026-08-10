package cz.bankintel.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CatalogFollowupSeriesResolverTest {

    @Test
    void resolvesOnlyExactIdsChosenByPlannerInPlannerOrder() {
        List<Map<String, Object>> available = List.of(
                series("inflation", "imf", "INF_US", "Inflation USA"),
                series("gold", "fred", "GOLD", "Gold price"),
                series("shelter", "fred", "CPI_SHELTER", "Shelter CPI"));
        CatalogFollowupPlan plan = plan(List.of("gold", "inflation"));

        List<Map<String, Object>> resolved = CatalogFollowupSeriesResolver.resolve(plan, available);

        assertThat(resolved).extracting(row -> row.get("ref_id"))
                .containsExactly("gold", "inflation");
    }

    @Test
    void neverSubstitutesAnInventedIdWithTopRankedSeries() {
        List<Map<String, Object>> available = List.of(
                series("inflation", "imf", "INF_US", "Inflation USA"),
                series("gold", "fred", "GOLD", "Gold price"));

        assertThat(CatalogFollowupSeriesResolver.resolve(plan(List.of("invented", "gold")), available))
                .extracting(row -> row.get("ref_id"))
                .containsExactly("gold");
    }

    private static Map<String, Object> series(String refId, String source, String setId, String title) {
        return Map.of("ref_id", refId, "source_type", source, "set_id", setId, "title", title);
    }

    private static CatalogFollowupPlan plan(List<String> ids) {
        return new CatalogFollowupPlan(
                "same_topic",
                "compose",
                "compose_multi_chart",
                true,
                "",
                new CatalogFollowupPlan.SourceConstraints("keep", List.of(), List.of()),
                ids,
                0.98,
                "compose requested series",
                "llm");
    }
}
