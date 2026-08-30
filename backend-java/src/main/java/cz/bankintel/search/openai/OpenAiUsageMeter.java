package cz.bankintel.search.openai;

import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Process-wide accounting of OpenAI token spend, split per {@link OpenAiModelTask}.
 *
 * <p>Without this the only record of what the AI layer costs is the OpenAI dashboard, which cannot
 * attribute spend to a task (planner vs. reranker vs. chat synthesis). Counters are in-memory and
 * reset on restart — they exist to make runaway usage visible, not to be a billing ledger.
 */
@Service
public class OpenAiUsageMeter {

    private static final Logger log = LoggerFactory.getLogger(OpenAiUsageMeter.class);

    private final Map<OpenAiModelTask, TaskUsage> byTask = new EnumMap<>(OpenAiModelTask.class);
    private final TaskUsage webSearch = new TaskUsage();
    private final Map<String, AtomicLong> callsByProvider = new ConcurrentHashMap<>();
    private final AtomicLong truncatedResponses = new AtomicLong();
    private final Instant startedAt = Instant.now();

    public OpenAiUsageMeter() {
        for (OpenAiModelTask task : OpenAiModelTask.values()) {
            byTask.put(task, new TaskUsage());
        }
    }

    public void record(
            OpenAiModelTask task,
            String model,
            Integer promptTokens,
            Integer completionTokens,
            String finishReason) {
        record(OpenAiClient.PROVIDER, task, model, promptTokens, completionTokens, finishReason);
    }

    /**
     * @param provider which backend served the call — {@code openai} or {@code local}. Tracked
     *     separately so a spike in local-model traffic reads as "OpenAI is failing over", not as a
     *     drop in AI usage.
     */
    public void record(
            String provider,
            OpenAiModelTask task,
            String model,
            Integer promptTokens,
            Integer completionTokens,
            String finishReason) {
        callsByProvider
                .computeIfAbsent(provider == null ? "unknown" : provider, key -> new AtomicLong())
                .incrementAndGet();
        TaskUsage usage = byTask.get(task);
        if (usage != null) {
            usage.add(promptTokens, completionTokens);
        }
        boolean truncated = "length".equalsIgnoreCase(finishReason);
        if (truncated) {
            truncatedResponses.incrementAndGet();
            // A hit cap silently shortens user-facing commentary, so surface it loudly enough to tune.
            log.warn(
                    "OpenAI response hit the completion-token cap: task={} model={} completion_tokens={}."
                            + " Raise bankintel.openai.max-completion-tokens-{} if answers look cut off.",
                    task,
                    model,
                    completionTokens,
                    task.name().toLowerCase());
        }
        if (log.isDebugEnabled()) {
            log.debug(
                    "OpenAI usage task={} model={} prompt_tokens={} completion_tokens={} finish_reason={}",
                    task,
                    model,
                    promptTokens,
                    completionTokens,
                    finishReason);
        }
    }

    public void recordWebSearch(String model, Integer promptTokens, Integer completionTokens) {
        webSearch.add(promptTokens, completionTokens);
        callsByProvider.computeIfAbsent(OpenAiClient.PROVIDER, key -> new AtomicLong()).incrementAndGet();
        if (log.isDebugEnabled()) {
            log.debug(
                    "OpenAI usage task=WEB_SEARCH model={} prompt_tokens={} completion_tokens={}",
                    model,
                    promptTokens,
                    completionTokens);
        }
    }

    /** Snapshot for the admin/health surface. Ordered map so the JSON reads predictably. */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("since", startedAt.toString());
        long totalPrompt = 0;
        long totalCompletion = 0;
        long totalCalls = 0;
        Map<String, Object> tasks = new LinkedHashMap<>();
        for (Map.Entry<OpenAiModelTask, TaskUsage> entry : byTask.entrySet()) {
            TaskUsage usage = entry.getValue();
            tasks.put(entry.getKey().name().toLowerCase(), usage.toMap());
            totalPrompt += usage.promptTokens.get();
            totalCompletion += usage.completionTokens.get();
            totalCalls += usage.calls.get();
        }
        tasks.put("web_search", webSearch.toMap());
        totalPrompt += webSearch.promptTokens.get();
        totalCompletion += webSearch.completionTokens.get();
        totalCalls += webSearch.calls.get();
        out.put("tasks", tasks);
        out.put("total_calls", totalCalls);
        out.put("total_prompt_tokens", totalPrompt);
        out.put("total_completion_tokens", totalCompletion);
        out.put("total_tokens", totalPrompt + totalCompletion);
        Map<String, Object> providers = new LinkedHashMap<>();
        callsByProvider.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> providers.put(entry.getKey(), entry.getValue().get()));
        out.put("calls_by_provider", providers);
        out.put("truncated_responses", truncatedResponses.get());
        return out;
    }

    private static final class TaskUsage {
        private final AtomicLong calls = new AtomicLong();
        private final AtomicLong promptTokens = new AtomicLong();
        private final AtomicLong completionTokens = new AtomicLong();

        void add(Integer prompt, Integer completion) {
            calls.incrementAndGet();
            if (prompt != null) {
                promptTokens.addAndGet(prompt);
            }
            if (completion != null) {
                completionTokens.addAndGet(completion);
            }
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("calls", calls.get());
            map.put("prompt_tokens", promptTokens.get());
            map.put("completion_tokens", completionTokens.get());
            return map;
        }
    }
}
