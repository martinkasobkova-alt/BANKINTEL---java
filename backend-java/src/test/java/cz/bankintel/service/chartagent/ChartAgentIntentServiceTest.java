package cz.bankintel.service.chartagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.openai.OpenAiJsonSupport;
import cz.bankintel.search.openai.OpenAiModelTask;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ChartAgentIntentServiceTest {

    @Mock
    private OpenAiJsonSupport openAiJsonSupport;

    private ChartAgentIntentService service;

    @BeforeEach
    void setUp() {
        service = new ChartAgentIntentService(openAiJsonSupport, new ObjectMapper());
        ReflectionTestUtils.setField(service, "openAiApiKey", "test-key");
    }

    @Test
    void inheritChartContextCannotIntroduceUnrelatedEntity() {
        when(openAiJsonSupport.chatJsonObject(anyString(), anyString(), eq(OpenAiModelTask.PLANNER)))
                .thenReturn(Map.of(
                        "intent", "add_series",
                        "catalog_query", "interest rates",
                        "catalog_queries", List.of("interest rates", "úrokové míry"),
                        "rewritten_question", "Přidej úrokové míry.",
                        "context_mode", "inherit_chart",
                        "context_terms", List.of("Argentina"),
                        "confidence", 0.96,
                        "reason_cz", "Navazuje na aktivní země."));

        Map<String, Object> result = service.interpretIntent(payload("Přidej úrokové míry."));

        assertThat(result.get("context_mode")).isEqualTo("inherit_chart");
        assertThat(result.get("context_terms")).isEqualTo(List.of("Czechia", "Austria", "Bulgaria"));
        assertThat(result.get("catalog_queries")).isEqualTo(List.of("interest rates", "úrokové míry"));
    }

    @Test
    void explicitEntityOverridesActiveChartEntities() {
        when(openAiJsonSupport.chatJsonObject(anyString(), anyString(), eq(OpenAiModelTask.PLANNER)))
                .thenReturn(Map.of(
                        "intent", "add_series",
                        "catalog_query", "Argentina interest rates",
                        "catalog_queries", List.of("Argentina interest rates"),
                        "rewritten_question", "Přidej úrokové míry Argentiny.",
                        "context_mode", "explicit",
                        "context_terms", List.of("Argentina"),
                        "confidence", 0.98,
                        "reason_cz", "Uživatel výslovně zadal Argentinu."));

        Map<String, Object> result = service.interpretIntent(payload("Přidej úrokové míry Argentiny."));

        assertThat(result.get("context_mode")).isEqualTo("explicit");
        assertThat(result.get("context_terms")).isEqualTo(List.of("Argentina"));
    }

    @Test
    void unavailableLlmDoesNotGuessSemanticIntentFromKeywords() {
        ReflectionTestUtils.setField(service, "openAiApiKey", "");

        Map<String, Object> result = service.interpretIntent(payload("add inflation to the chart"));

        assertThat(result.get("intent")).isEqualTo("unknown");
        assertThat(result.get("source")).isEqualTo("fallback");
        assertThat(result.get("confidence")).isEqualTo(0.0);
    }

    @Test
    void llmContractSeparatesContextualTimelineFromQuantitativeSeries() throws Exception {
        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        when(openAiJsonSupport.chatJsonObject(systemPrompt.capture(), anyString(), eq(OpenAiModelTask.PLANNER)))
                .thenReturn(Map.of(
                        "intent", "research_web",
                        "catalog_query", "",
                        "catalog_queries", List.of(),
                        "rewritten_question", "Dohledat a vyznačit celé období českých vlád v grafu.",
                        "context_mode", "explicit",
                        "context_terms", List.of("Czechia"),
                        "confidence", 0.99,
                        "reason_cz", "Jde o externí časový kontext, nikoli datovou řadu."));

        Map<String, Object> result = service.interpretIntent(
                payload("Přidej do grafu souvislou časovou osu českých vlád."));

        assertThat(result.get("intent")).isEqualTo("research_web");
        assertThat(systemPrompt.getValue())
                .contains("Rozlišuj objekt akce, ne sloveso uživatele")
                .contains("nová kvantitativní datová řada");
    }

    @Test
    void stockChartExposesMetricHintSoAddSeriesStaysOnSamePriceMetric() throws Exception {
        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userContext = ArgumentCaptor.forClass(String.class);
        when(openAiJsonSupport.chatJsonObject(
                        systemPrompt.capture(), userContext.capture(), eq(OpenAiModelTask.PLANNER)))
                .thenReturn(Map.of(
                        "intent", "add_series",
                        "catalog_query", "Alphabet Inc. stock price",
                        "catalog_queries", List.of("Alphabet Inc. stock price"),
                        "rewritten_question", "Přidej Alphabet Inc.",
                        "context_mode", "global",
                        "context_terms", List.of(),
                        "confidence", 0.9,
                        "reason_cz", "Stejná metrika jako aktuální graf."));

        // Regresní test pro bug: "přidej google" na akciovém grafu (bez zmínky metriky) se
        // dřív místo ceny akcie vyhodnotilo jako "Alphabet Inc. revenue" - LLM neviděl, že jde
        // o cenu akcie, protože context obsahoval jen holé názvy řad, ne jejich jednotku/zdroj.
        Map<String, Object> stockPayload = Map.of(
                "question", "přidej google",
                "chart_contract", Map.of(
                        "title", "Apple Inc.",
                        "series", List.of(Map.of("id", "aapl", "label", "Apple Inc.")),
                        "data", List.of(Map.of(
                                "series_id", "aapl",
                                "period", "2026-07-31",
                                "value_raw", 308.91,
                                "unit", "USD",
                                "source", "yahoo_finance"))),
                "conversation_history", List.of());

        Map<String, Object> result = service.interpretIntent(stockPayload);

        assertThat(result.get("catalog_query")).isEqualTo("Alphabet Inc. stock price");
        assertThat(userContext.getValue()).contains("\"series_metric_hint\":\"USD\"");
        assertThat(systemPrompt.getValue())
                .contains("series_metric_hint")
                .contains("cílit na stejný typ metriky");
        // Regresní test pro druhý bug objevený při stejném ověřování: přidání akcie do grafu s
        // více firmami (Apple/Netflix) se dřív vyhodnotilo jako inherit_chart vůči sesterským
        // firmám, což ve fronetendu (contextualAddSeriesSearchQueries) vytvořilo zmatené spojené
        // dotazy typu "Alphabet Inc. share price apple inc." a skončilo ověřením špatné řady.
        assertThat(systemPrompt.getValue())
                .contains("NIKDY ho nepoužij")
                .contains("context_mode vždy global");
    }

    @Test
    void repairsCatalogQueryWhenLlmTargetsOldEntityDespiteCorrectContextTerms() throws Exception {
        // Živě reprodukováno: "ridej google" (překlep "přidej") na grafu s Apple Inc. LLM správně
        // pochopil, že jde o Alphabet Inc./Google (context_terms, rewritten_question), ale
        // catalog_query/catalog_queries omylem zůstaly o Apple Inc. - jako by zkopíroval frázi
        // z předchozího kola místo aby dosadil novou firmu.
        when(openAiJsonSupport.chatJsonObject(anyString(), anyString(), eq(OpenAiModelTask.PLANNER)))
                .thenReturn(Map.of(
                        "intent", "add_series",
                        "catalog_query", "Apple Inc. stock price (adjusted close)",
                        "catalog_queries", List.of(
                                "Apple Inc. adjusted close price",
                                "Apple Inc. stock price",
                                "AAPL adjusted close",
                                "AAPL stock price"),
                        "rewritten_question",
                        "Přidej do grafu řadu pro Google (Alphabet Inc.) – cenu akcie (adjusted close).",
                        "context_mode", "global",
                        "context_terms", List.of("Alphabet Inc.", "Google", "GOOGL", "GOOG"),
                        "confidence", 0.62,
                        "reason_cz", "Uživatel napsal „ridej google“ (překlep „přidej“)."));

        Map<String, Object> stockPayload = Map.of(
                "question", "ridej google",
                "chart_contract", Map.of(
                        "title", "Apple Inc.",
                        "series", List.of(Map.of("id", "aapl", "label", "Apple Inc.")),
                        "data", List.of(Map.of(
                                "series_id", "aapl",
                                "period", "2026-07-31",
                                "value_raw", 308.91,
                                "unit", "USD",
                                "source", "yahoo_finance"))),
                "conversation_history", List.of());

        Map<String, Object> result = service.interpretIntent(stockPayload);

        assertThat(result.get("catalog_query")).isEqualTo("Alphabet Inc. adjusted close price");
        assertThat(result.get("catalog_queries"))
                .isEqualTo(List.of(
                        "Alphabet Inc. adjusted close price",
                        "Alphabet Inc. stock price",
                        "Alphabet Inc. adjusted close"));
    }

    private static Map<String, Object> payload(String question) {
        return Map.of(
                "question", question,
                "chart_contract", Map.of(
                        "title", "Return on equity of banks",
                        "series", List.of(
                                Map.of("id", "cz", "label", "Czechia"),
                                Map.of("id", "at", "label", "Austria"),
                                Map.of("id", "bg", "label", "Bulgaria")),
                        "data", List.of(
                                Map.of("series_id", "cz", "period", "2025", "value_raw", 16.1),
                                Map.of("series_id", "at", "period", "2025", "value_raw", 8.0),
                                Map.of("series_id", "bg", "period", "2025", "value_raw", 11.3))),
                "conversation_history", List.of());
    }
}
