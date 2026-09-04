package cz.bankintel.search.openai;

import cz.bankintel.util.BankIntelEnvVars;

/**
 * Deployment-time choice of which provider {@link OpenAiClient} treats as primary. Independent of
 * {@link LocalLlmFallbackClient}, which remains available as a resilience fallback regardless of which
 * primary is selected.
 */
enum LlmProviderKind {
    OPENAI,
    ANTHROPIC;

    static LlmProviderKind resolve() {
        String value = BankIntelEnvVars.get("BANKINTEL_LLM_PROVIDER");
        return "anthropic".equalsIgnoreCase(value == null ? "" : value.trim()) ? ANTHROPIC : OPENAI;
    }
}
