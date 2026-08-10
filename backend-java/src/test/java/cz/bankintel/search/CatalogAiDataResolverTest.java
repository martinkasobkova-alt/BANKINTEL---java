package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.openai.OpenAiModelTask;
import cz.bankintel.search.openai.OpenAiClient;
import cz.bankintel.search.openai.OpenAiJsonSupport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CatalogAiDataResolverTest {

    @Mock
    private OpenAiJsonSupport openAiJsonSupport;

    @Mock
    private OpenAiClient openAiClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void returnsCandidatesUnchangedWhenOpenAiNotConfigured() {
        when(openAiClient.isConfigured()).thenReturn(false);
        CatalogAiDataResolver resolver = new CatalogAiDataResolver(openAiJsonSupport, openAiClient, objectMapper);
        List<Map<String, Object>> candidates = List.of(Map.of("set_id", "a"), Map.of("set_id", "b"));

        // Fail-safe: without a key the deterministic lexical order must pass through untouched.
        assertSame(candidates, resolver.rankCandidates("eurusd", candidates));
    }

    @Test
    void relevanceMarkersAreReadCorrectly() {
        assertTrue(CatalogAiDataResolver.isAiRelevant(Map.of("_ai_relevant", true)));
        assertFalse(CatalogAiDataResolver.isAiRelevant(Map.of("_ai_relevant", false)));
        assertTrue(CatalogAiDataResolver.isAiRejected(Map.of("_ai_relevant", false)));
        assertFalse(CatalogAiDataResolver.isAiRejected(Map.of("_ai_relevant", true)));
        assertFalse(CatalogAiDataResolver.isAiRelevant(Map.of()));
        assertFalse(CatalogAiDataResolver.isAiRejected(Map.of()));
    }

    @Test
    void emptyAiSelectionFallsBackWhenDeterministicMatchIsStrong() throws Exception {
        when(openAiClient.isConfigured()).thenReturn(true);
        when(openAiJsonSupport.chatJsonObject(anyString(), anyString(), eq(OpenAiModelTask.CHAT)))
                .thenReturn(Map.of("relevant", List.of()));
        CatalogAiDataResolver resolver = new CatalogAiDataResolver(openAiJsonSupport, openAiClient, objectMapper);
        List<Map<String, Object>> candidates = List.of(
                row("eurostat", "prc_hicp_manr", "HICP - monthly data (annual rate of change) (1997-2025)", 123),
                row("oecd4", "housing_prices", "Analytical house prices indicators", 10));

        List<Map<String, Object>> ranked = resolver.rankCandidates("inflace madarsko", candidates);

        assertSame(candidates, ranked);
        assertFalse(candidates.getFirst().containsKey("_ai_relevant"));
    }

    private static Map<String, Object> row(String source, String setId, String title, int score) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("source_type", source);
        row.put("catalog_id", source);
        row.put("set_id", setId);
        row.put("title", title);
        row.put("_search_score", score);
        return row;
    }
}
