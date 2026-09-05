package cz.bankintel.search.openai;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One completed LLM call, independent of which provider served it.
 *
 * <p>Shared by {@link OpenAiClient} and {@link LocalLlmFallbackClient} so failover can return the
 * same shape as the primary path and callers never learn which provider answered.
 */
record LlmAttempt(
        JsonNode json,
        int statusCode,
        long elapsedMs,
        Integer promptTokens,
        Integer completionTokens,
        int contentChars,
        String finishReason) {}
