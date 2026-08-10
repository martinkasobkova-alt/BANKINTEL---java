package cz.bankintel.search.openai;

/** Per-task OpenAI model selection — ref Python commit 4d9a817 (chat vs planner split). */
public enum OpenAiModelTask {
    /** Chat, answer, chart economist, explore synthesis, data resolver. */
    CHAT,
    /** Query planner, intent classification. */
    PLANNER,
    /** High-volume candidate classification; uses a dedicated fast model. */
    RERANKER
}
