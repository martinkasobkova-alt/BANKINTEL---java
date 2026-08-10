package cz.bankintel.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.v2.orchestration.SearchV2FeatureFlags;
import cz.bankintel.search.v2.orchestration.SearchV2Service;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
@RequiredArgsConstructor
public class CatalogSearchStreamService {

    private static final long SSE_TIMEOUT_MS = 180_000L;

    private final CatalogMultiSearchService multiSearchService;
    private final CatalogDeepSearchService deepSearchService;
    private final SearchV2Service searchV2Service;
    private final SearchV2FeatureFlags searchV2FeatureFlags;
    private final CatalogFollowupService followupService;
    private final ObjectMapper objectMapper;

    public SseEmitter streamMultiSearch(String query, List<String> sources, Integer limit) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        CompletableFuture.runAsync(() -> {
            try {
                sendRaw(emitter, Map.of("event", "stream_open", "query_len", query.length(), "source_count", sources.size()));
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("query", query);
                payload.put("q", query);
                payload.put("sources", sources);
                if (limit != null) {
                    payload.put("limit", limit);
                }
                Map<String, Object> finalBody = multiSearchService.multiSearch(payload, (source, hits) -> {
                    try {
                        sendNamedEvent(emitter, "lane", Map.of(
                                "source", source,
                                "hits", hits,
                                "count", hits.size(),
                                "themes", List.of()));
                    } catch (IOException ex) {
                        throw new RuntimeException(ex);
                    }
                });
                sendNamedEvent(emitter, "final", finalMultiPayload(finalBody, query));
                sendRaw(emitter, Map.of("event", "search_finished", "payload", finalBody));
                emitter.complete();
            } catch (Exception ex) {
                emitter.completeWithError(ex);
            }
        });
        return emitter;
    }

    public SseEmitter streamDeepSearch(String query, List<String> sources, Boolean useAi) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        CompletableFuture.runAsync(() -> {
            try {
                sendRaw(emitter, Map.of("event", "stream_open", "query_len", query.length()));
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("q", query);
                payload.put("query", query);
                if (sources != null && !sources.isEmpty()) {
                    payload.put("sources", sources);
                }
                if (useAi != null) {
                    payload.put("use_ai", useAi);
                }
                // Results must not wait for a second LLM call that only writes narrative prose.
                payload.put("use_ai_story", false);
                Map<String, Object> result = searchV2FeatureFlags.useV2(payload)
                        ? searchV2Service.search(payload)
                        : deepSearchService.deepSearchWithLanes(payload, lane -> {
                            try {
                                sendNamedEvent(emitter, "lane", lane);
                            } catch (IOException ex) {
                                throw new RuntimeException(ex);
                            }
                        });
                Map<String, Object> conversation = followupService.bootstrapConversation(result, null, payload, query);
                result.put("conversation", conversation);
                sendNamedEvent(emitter, "final", finalDeepPayload(result));
                sendRaw(emitter, Map.of("event", "search_finished", "payload", result));
                emitter.complete();
            } catch (Exception ex) {
                emitter.completeWithError(ex);
            }
        });
        return emitter;
    }

    private Map<String, Object> finalMultiPayload(Map<String, Object> payload, String query) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) payload.getOrDefault("results", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> summaries = (List<Map<String, Object>>) payload.getOrDefault("source_summaries", List.of());
        Map<String, Object> sourceStatuses = new LinkedHashMap<>();
        for (Map<String, Object> summary : summaries) {
            String id = String.valueOf(summary.getOrDefault("id", ""));
            if (!id.isBlank()) {
                sourceStatuses.put(id, summary);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hits", results);
        out.put("themes", payload.getOrDefault("themes_triggered", List.of()));
        out.put("source_statuses", sourceStatuses);
        out.put("source_summaries", summaries);
        out.put("message_cs", payload.get("message_cs"));
        out.put("elapsed_ms", payload.get("elapsed_ms"));
        out.put("query", payload.getOrDefault("query", query));
        out.put("answer", payload.get("answer"));
        return out;
    }

    private Map<String, Object> finalDeepPayload(Map<String, Object> payload) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> verified = (List<Map<String, Object>>) payload.getOrDefault("verified", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> possible = (List<Map<String, Object>>) payload.getOrDefault("possible", List.of());
        List<Map<String, Object>> rows = new ArrayList<>(verified);
        rows.addAll(possible);
        Map<String, Object> out = new LinkedHashMap<>(payload);
        out.put("rows", rows);
        out.put("diagnostics", Map.of("lane_timings", laneTimings(payload)));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> laneTimings(Map<String, Object> payload) {
        Map<String, Integer> out = new LinkedHashMap<>();
        Object raw = payload.get("source_statuses");
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> row) {
                String source = String.valueOf(((Map<String, Object>) row).getOrDefault("source", ""));
                Object ms = ((Map<String, Object>) row).get("duration_ms");
                if (!source.isBlank() && ms instanceof Number n) {
                    out.put(source, n.intValue());
                }
            }
        }
        return out;
    }

    private void sendNamedEvent(SseEmitter emitter, String eventName, Map<String, Object> body) throws IOException {
        Map<String, Object> packet = new LinkedHashMap<>(body);
        packet.remove("event");
        emitter.send(SseEmitter.event()
                .name(eventName)
                .data(objectMapper.writeValueAsString(packet), MediaType.APPLICATION_JSON));
    }

    private void sendRaw(SseEmitter emitter, Map<String, Object> packet) throws IOException {
        emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(packet), MediaType.APPLICATION_JSON));
    }
}
