package cz.bankintel.search.openai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Guards the cost ceiling: CHAT synthesis used to send no {@code max_completion_tokens} at all, so
 * a single request could bill the model's whole output window.
 */
class OpenAiCompletionCapTest {

    private final OpenAiClient client = new OpenAiClient(new OpenAiUsageMeter(), new LocalLlmFallbackClient());

    @Test
    void everyTaskHasAPositiveCompletionCap() {
        for (OpenAiModelTask task : OpenAiModelTask.values()) {
            assertThat(client.maxCompletionTokensFor(task))
                    .as("completion cap for %s", task)
                    .isPositive();
        }
    }

    @Test
    void fallsBackToBuiltInDefaultsWhenNothingIsConfigured() {
        assertThat(client.maxCompletionTokensFor(OpenAiModelTask.CHAT)).isEqualTo(4000);
        assertThat(client.maxCompletionTokensFor(OpenAiModelTask.PLANNER)).isEqualTo(900);
        assertThat(client.maxCompletionTokensFor(OpenAiModelTask.RERANKER)).isEqualTo(3000);
        assertThat(client.configuredWebSearchMaxOutputTokens()).isEqualTo(4000);
    }

    @Test
    void plannerStaysTighterThanChatSoSearchLatencyIsNotTradedForOutputLength() {
        assertThat(client.maxCompletionTokensFor(OpenAiModelTask.PLANNER))
                .isLessThan(client.maxCompletionTokensFor(OpenAiModelTask.CHAT));
    }

    @Test
    void meterCountsTokensPerTaskAndFlagsTruncatedAnswers() {
        OpenAiUsageMeter meter = new OpenAiUsageMeter();
        meter.record(OpenAiModelTask.CHAT, "gpt-5.4-mini", 1200, 800, "stop");
        meter.record(OpenAiModelTask.CHAT, "gpt-5.4-mini", 300, 4000, "length");
        meter.record(OpenAiModelTask.PLANNER, "gpt-5.4-nano", 500, 120, "stop");
        meter.recordWebSearch("gpt-5.4-mini", 90, 40);

        Map<String, Object> snapshot = meter.snapshot();

        assertThat(snapshot).containsEntry("total_calls", 4L);
        assertThat(snapshot).containsEntry("total_prompt_tokens", 2090L);
        assertThat(snapshot).containsEntry("total_completion_tokens", 4960L);
        assertThat(snapshot).containsEntry("total_tokens", 7050L);
        assertThat(snapshot).containsEntry("truncated_responses", 1L);

        @SuppressWarnings("unchecked")
        Map<String, Object> tasks = (Map<String, Object>) snapshot.get("tasks");
        @SuppressWarnings("unchecked")
        Map<String, Object> chat = (Map<String, Object>) tasks.get("chat");
        assertThat(chat).containsEntry("calls", 2L);
        assertThat(chat).containsEntry("completion_tokens", 4800L);
    }

    @Test
    void meterToleratesMissingUsageBlock() {
        OpenAiUsageMeter meter = new OpenAiUsageMeter();
        meter.record(OpenAiModelTask.CHAT, "gpt-5.4-mini", null, null, null);

        Map<String, Object> snapshot = meter.snapshot();

        assertThat(snapshot).containsEntry("total_calls", 1L);
        assertThat(snapshot).containsEntry("total_tokens", 0L);
        assertThat(snapshot).containsEntry("truncated_responses", 0L);
    }
}
