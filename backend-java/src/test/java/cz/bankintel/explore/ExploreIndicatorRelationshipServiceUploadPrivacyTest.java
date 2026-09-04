package cz.bankintel.explore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.openai.OpenAiClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Živě zjištěno: appka `request.uploadIds()` do detailní Explorer analýzy vůbec nezapojovala.
 * Zapojení bylo záměrně navrženo tak, aby korelace/trend/medián nikdy nepárovaly nahranou
 * uživatelskou řadu za „Strict private" - i kdyby ji LLM navrhlo, {@code candidates} ji vůbec
 * neobsahuje, takže {@code computeOne} vrátí null.
 */
class ExploreIndicatorRelationshipServiceUploadPrivacyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // computeCorrelation vyžaduje MIN_OVERLAP_FOR_CORRELATION=6 společných období - míň by
    // vrátilo null bez ohledu na privacy mode a test by nic neprokázal.
    private static Map<String, Object> catalogItem() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("set_id", "gdp");
        out.put("title", "HDP");
        out.put("source_type", "eurostat");
        out.put("observations", List.of(
                Map.of("period", "2024-01", "value", 100.0),
                Map.of("period", "2024-02", "value", 101.0),
                Map.of("period", "2024-03", "value", 103.0),
                Map.of("period", "2024-04", "value", 104.0),
                Map.of("period", "2024-05", "value", 106.0),
                Map.of("period", "2024-06", "value", 107.0),
                Map.of("period", "2024-07", "value", 109.0)));
        return out;
    }

    private static Map<String, Object> uploadItem() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("set_id", "upload-1");
        out.put("title", "trzby.csv");
        out.put("source_type", "user_upload");
        out.put("observations", List.of(
                Map.of("period", "2024-01", "value", 10.0),
                Map.of("period", "2024-02", "value", 12.0),
                Map.of("period", "2024-03", "value", 15.0),
                Map.of("period", "2024-04", "value", 17.0),
                Map.of("period", "2024-05", "value", 20.0),
                Map.of("period", "2024-06", "value", 22.0),
                Map.of("period", "2024-07", "value", 25.0)));
        return out;
    }

    private static JsonNode proposalReferencingBoth() throws Exception {
        return MAPPER.readTree(
                """
                {"relationships":[{"type":"correlation","series_a":"gdp","series_b":"upload-1","reason":"test"}]}
                """);
    }

    @Test
    void strictPrivateExcludesUploadedSeriesFromCandidatesEvenIfProposed() throws Exception {
        OpenAiClient openAiClient = mock(OpenAiClient.class);
        when(openAiClient.isConfigured()).thenReturn(true);
        when(openAiClient.chatCompletionJson(any(), any(), any(), anyBoolean())).thenReturn(proposalReferencingBoth());
        ExploreIndicatorRelationshipService service = new ExploreIndicatorRelationshipService(openAiClient);

        ExploreIndicatorRelationshipService.RelationshipsResult result = service.analyze(
                List.of(catalogItem(), uploadItem()), "otázka", "sektor", ExploreUserDataPrivacy.STRICT_PRIVATE);

        assertThat(result.relationships()).isEmpty();
        assertThat(result.digest()).doesNotContain("trzby.csv");
    }

    @Test
    void safeSummaryAllowsUploadedSeriesIntoCandidates() throws Exception {
        OpenAiClient openAiClient = mock(OpenAiClient.class);
        when(openAiClient.isConfigured()).thenReturn(true);
        when(openAiClient.chatCompletionJson(any(), any(), any(), anyBoolean())).thenReturn(proposalReferencingBoth());
        ExploreIndicatorRelationshipService service = new ExploreIndicatorRelationshipService(openAiClient);

        ExploreIndicatorRelationshipService.RelationshipsResult result = service.analyze(
                List.of(catalogItem(), uploadItem()), "otázka", "sektor", ExploreUserDataPrivacy.SAFE_SUMMARY);

        assertThat(result.relationships()).hasSize(1);
        assertThat(result.relationships().getFirst().get("series_b_title")).isEqualTo("trzby.csv");
    }

    @Test
    void threeArgOverloadDefaultsToStrictPrivate() throws Exception {
        OpenAiClient openAiClient = mock(OpenAiClient.class);
        when(openAiClient.isConfigured()).thenReturn(true);
        when(openAiClient.chatCompletionJson(any(), any(), any(), anyBoolean())).thenReturn(proposalReferencingBoth());
        ExploreIndicatorRelationshipService service = new ExploreIndicatorRelationshipService(openAiClient);

        ExploreIndicatorRelationshipService.RelationshipsResult result =
                service.analyze(List.of(catalogItem(), uploadItem()), "otázka", "sektor");

        assertThat(result.relationships()).isEmpty();
    }
}
