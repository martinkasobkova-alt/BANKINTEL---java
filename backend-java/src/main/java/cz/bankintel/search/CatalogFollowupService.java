package cz.bankintel.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.search.openai.OpenAiClient;
import cz.bankintel.search.v2.orchestration.SearchV2FeatureFlags;
import cz.bankintel.search.v2.orchestration.SearchV2Service;
import cz.bankintel.service.research.WebResearchService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CatalogFollowupService {

    private static final Logger log = LoggerFactory.getLogger(CatalogFollowupService.class);
    private static final long TEMP_CONV_TTL_SEC = 45 * 60;

    private final CatalogDeepSearchService deepSearchService;
    private final SearchV2Service searchV2Service;
    private final SearchV2FeatureFlags searchV2FeatureFlags;
    private final CatalogFollowupActionHandler actionHandler;
    private final WebResearchService webResearchService;
    private final OpenAiClient openAiClient;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, Map<String, Object>> conversations = new ConcurrentHashMap<>();

    public Map<String, Object> bootstrapConversation(
            Map<String, Object> deepResult, UserEntity user, Map<String, Object> searchPayload, String userQuery) {
        cleanExpired();
        String cid = UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        List<Map<String, Object>> foundSummary = summarizeFoundSeriesForContext(deepResult);
        List<Map<String, Object>> selectedRefs = extractSeriesRefs(deepResult);

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("mode", stringValue(searchPayload, "mode", "single"));
        context.put("sources", searchPayload.getOrDefault("sources", List.of()));
        context.put("use_ai", searchPayload.getOrDefault("use_ai", true));
        String rootQuery = userQuery == null ? "" : userQuery.trim();
        context.put("root_query", rootQuery);
        context.put("last_user_message", rootQuery);
        context.put("last_search_query", rootQuery);
        context.put("current_focus_query", rootQuery);
        context.put("last_query", rootQuery);
        context.put("selected_series_refs", selectedRefs);
        context.put("available_series_refs", selectedRefs);
        context.put("found_series_summary", foundSummary);
        context.put("topic_anchor", CatalogFollowupQuerySupport.topicAnchorFromFoundSummary(foundSummary));

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("conversation_id", cid);
        doc.put("user_id", user != null ? user.getId() : null);
        doc.put("messages", List.of(message("user", userQuery)));
        doc.put("context", context);
        doc.put("created_at", now.toEpochMilli() / 1000.0);
        doc.put("updated_at", now.toEpochMilli() / 1000.0);
        doc.put("expires_at", now.toEpochMilli() / 1000.0 + TEMP_CONV_TTL_SEC);
        conversations.put(cid, doc);

        return Map.of(
                "id", cid,
                "persistence", "temporary",
                "allowed_followups", allowedFollowups());
    }

    public Map<String, Object> followup(UserEntity user, Map<String, Object> payload) {
        String cid = stringValue(payload, "conversation_id", "");
        String message = stringValue(payload, "message", "");
        if (message.length() < 2) {
            return Map.of("ok", false, "error", "Follow-up dotaz je příliš krátký.");
        }
        Map<String, Object> doc = loadConversation(cid, user);
        boolean conversationRecovered = false;
        if (doc == null) {
            doc = recoverConversation(payload, user);
            if (doc == null) {
                return Map.of(
                        "ok", false,
                        "error_code", "conversation_not_found",
                        "retryable", true,
                        "error", "Konverzace nebyla nalezena nebo k ní nemáte přístup.");
            }
            cid = stringValue(doc, "conversation_id", "");
            conversationRecovered = true;
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages =
                new ArrayList<>((List<Map<String, Object>>) doc.getOrDefault("messages", List.of()));
        messages.add(message("user", message));

        CatalogFollowupPlan followupPlan = CatalogFollowupPlan.fromPayload(payload.get("followup_plan"));
        String legacyActionHint = stringValue(payload, "action_hint", "").toLowerCase(Locale.ROOT);
        String actionHint = followupPlan != null ? followupPlan.action() : legacyActionHint;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("action", actionHint.isBlank() ? "chat" : actionHint);
        out.put("routing_source", followupPlan != null ? followupPlan.routingSource() : "legacy_fallback");
        if (followupPlan != null) {
            out.put("followup_plan", followupPlan.toMap());
        }
        out.put("conversation_recovered", conversationRecovered);

        @SuppressWarnings("unchecked")
        Map<String, Object> context = new LinkedHashMap<>((Map<String, Object>) doc.getOrDefault("context", Map.of()));
        List<Map<String, Object>> payloadAvailableRefs = normalizeRows(payload.get("available_series_refs"));
        List<Map<String, Object>> payloadSelectedRefs = normalizeRows(payload.get("selected_series_refs"));
        List<Map<String, Object>> contextAvailableRefs = normalizeRows(context.get("available_series_refs"));
        List<Map<String, Object>> contextSelectedRefs = normalizeRows(context.get("selected_series_refs"));
        List<Map<String, Object>> availableRefs = CatalogFollowupSeriesResolver.merge(
                payloadAvailableRefs, contextAvailableRefs, contextSelectedRefs);
        if (!payloadAvailableRefs.isEmpty()) {
            context.put("available_series_refs", new ArrayList<>(payloadAvailableRefs));
            context.put("found_series_summary", new ArrayList<>(payloadAvailableRefs));
        }
        List<Map<String, Object>> selectedRefs = followupPlan != null
                ? CatalogFollowupSeriesResolver.resolve(followupPlan, availableRefs)
                : CatalogFollowupSeriesResolver.merge(payloadSelectedRefs, contextSelectedRefs);

        if (followupPlan != null && "research".equals(followupPlan.operation())) {
            Map<String, Object> chartContext = researchContext(
                    normalizeMap(payload.get("chart_context")), context, availableRefs);
            Map<String, Object> researchResult = "annotate_web_events".equals(actionHint)
                    ? webResearchService.researchGraphEvents(followupPlan.searchQuery(), chartContext)
                    : webResearchService.discoverExternalDatasets(followupPlan.searchQuery(), chartContext);
            String assistantAnswer = stringValue(researchResult, "answer_cz", "Webový výzkum byl dokončen.");
            messages.add(message("assistant", assistantAnswer));
            doc.put("messages", messages);
            doc.put("context", context);
            doc.put("updated_at", Instant.now().toEpochMilli() / 1000.0);
            conversations.put(cid, doc);
            out.put("research_result", researchResult);
            out.put("conversation", Map.of("id", cid, "messages", messages));
            out.put("access_mode", "development_full");
            out.put("user_tier", user != null ? user.getRole() : "anonymous");
            return out;
        }

        CatalogFollowupActionHandler.FollowupActionResult actionResult =
                dispatchComputeAction(actionHint, message, selectedRefs, user);
        if (actionResult != null) {
            messages.add(message("assistant", actionResult.assistantAnswer()));
            doc.put("messages", messages);
            doc.put("context", context);
            doc.put("updated_at", Instant.now().toEpochMilli() / 1000.0);
            conversations.put(cid, doc);
            out.put("action", actionHint.isBlank() ? "chat" : actionHint);
            if (actionResult.computationResult() != null) {
                out.put("computation_result", actionResult.computationResult());
            }
            out.put("conversation", Map.of("id", cid, "messages", messages));
            out.put("access_mode", "development_full");
            out.put("user_tier", user != null ? user.getRole() : "anonymous");
            return out;
        }

        boolean executeSearch = followupPlan != null
                ? followupPlan.executesSearch()
                : "refine_search".equals(actionHint)
                        || "find_alternatives".equals(actionHint)
                        || looksLikeRefineSearch(message);
        if (executeSearch) {
            String effectiveQuery = resolveFollowupQuery(followupPlan, message, context);
            if (effectiveQuery.isBlank()) {
                effectiveQuery = message;
            }
            List<String> sources = resolveFollowupSources(followupPlan, message, context);
            Map<String, Object> searchPayload = new LinkedHashMap<>();
            searchPayload.put("q", effectiveQuery);
            searchPayload.put("query", effectiveQuery);
            searchPayload.put("sources", sources);
            searchPayload.put("mode", context.getOrDefault("mode", "single"));
            searchPayload.put("use_ai", context.getOrDefault("use_ai", true));
            if (context.get("limit_per_source") != null) {
                searchPayload.put("limit_per_source", context.get("limit_per_source"));
            }
            Map<String, Object> deepResult = searchV2FeatureFlags.useV2(searchPayload)
                    ? searchV2Service.search(searchPayload)
                    : deepSearchService.deepSearch(searchPayload);
            updateContextAfterRefine(context, message, effectiveQuery, deepResult);
            doc.put("context", context);
            out.put("deep_search_result", deepResult);
            String assistantAnswer = extractDeepSearchAssistantAnswer(deepResult);
            messages.add(message("assistant", assistantAnswer));
        } else {
            Map<String, Object> chat = resultsChat(user, Map.of(
                    "message", message,
                    "found_summary", summarizeFromContext(doc),
                    "conversation_history", messages));
            messages.add(message("assistant", String.valueOf(chat.getOrDefault("assistant_answer", ""))));
        }

        doc.put("messages", messages);
        doc.put("updated_at", Instant.now().toEpochMilli() / 1000.0);
        conversations.put(cid, doc);

        Map<String, Object> conversation = Map.of("id", cid, "messages", messages);
        out.put("conversation", conversation);
        out.put("access_mode", "development_full");
        out.put("user_tier", user != null ? user.getRole() : "anonymous");
        return out;
    }

    public Map<String, Object> resultsChat(UserEntity user, Map<String, Object> payload) {
        String message = stringValue(payload, "message", "");
        if (message.length() < 2) {
            return Map.of("ok", false, "error", "Dotaz je příliš krátký.");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = normalizeRows(payload.get("found_summary"));
        if (rows.isEmpty()) {
            rows = normalizeRows(payload.get("selected_series_refs"));
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> history =
                payload.get("conversation_history") instanceof List<?> list
                        ? (List<Map<String, Object>>) list
                        : List.of();

        String answer;
        try {
            answer = askEconomist(message, rows, history);
        } catch (Exception ex) {
            log.warn("results-chat economist failed: {}", ex.getMessage());
            answer = "Teď se mi nepodařilo odpovědět, zkuste to prosím znovu.";
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("assistant_answer", answer);
        out.put("filtered_set_ids", null);
        out.put("access_mode", "development_full");
        out.put("user_tier", user != null ? user.getRole() : "anonymous");
        return out;
    }

    public Map<String, Object> resultsIntent(Map<String, Object> payload) {
        String message = stringValue(payload, "message", "");
        if (message.isBlank()) {
            return Map.of("ok", false, "intent", "unknown", "source", "empty");
        }
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> history =
                    payload.get("conversation_history") instanceof List<?> list
                            ? (List<Map<String, Object>>) list
                            : List.of();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> foundSummary =
                    payload.get("found_summary") instanceof List<?> list
                            ? (List<Map<String, Object>>) list
                            : List.of();
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("message", message);
            context.put("has_selected_series", Boolean.TRUE.equals(payload.get("has_selected_series")));
            context.put("available_series", foundSummary.stream()
                    .limit(24)
                    .map(CatalogFollowupService::plannerSeriesRow)
                    .filter(r -> !String.valueOf(r.getOrDefault("ref_id", "")).isBlank())
                    .toList());
            context.put("conversation_history", history.stream().limit(8).toList());
            context.put("root_query", stringValue(payload, "root_query", ""));
            context.put("current_sources", payload.getOrDefault("current_sources", List.of()));

            String systemPrompt =
                    """
                    You are the semantic follow-up planner for an economic data application.
                    You alone decide the semantic relation and operation. The application will only
                    validate this bounded plan and execute it; it will not reinterpret the user's text.

                    Return JSON only with this exact shape:
                    {
                      "relation":"same_topic"|"new_topic",
                      "operation":"answer"|"search"|"compare"|"compute"|"compose"|"research",
                      "action":"chat_over_results"|"explain_indicator"|"refine_search"|
                               "find_alternatives"|"new_search"|"compare_selected"|
                               "compute_ratio"|"compute_difference"|"compute_percent_change"|
                               "compose_multi_chart"|"add_to_chart"|
                               "annotate_web_events"|"discover_external_series",
                      "preserve_concept":true|false,
                      "search_query":"self-contained query for the catalog, required for search",
                      "source_constraints":{
                        "mode":"keep"|"include"|"exclude"|"alternatives",
                        "include":["registered source ids"],
                        "exclude":["registered source ids"]
                      },
                      "series_ref_ids":["exact opaque ref_id from available_series"],
                      "confidence":0.0,
                      "reason_cz":"short explanation"
                    }

                    Registered source ids are: arad, csu, eurostat, ecb2, bis, imf, oecd4, fred, data360.
                    Use canonical ids only (ECB = ecb2, OECD = oecd4, World Bank Data360 = data360).
                    For same-topic source alternatives, use operation=search, action=find_alternatives,
                    preserve_concept=true, a self-contained search_query retaining the original concept,
                    and source_constraints mode=alternatives with the requested exclusions/inclusions.
                    If the user changes only the source, copy root_query exactly into search_query. Titles of
                    found series are evidence about the current result set, not permission to replace or narrow
                    the original concept, metric, or geography. Keep search_query as one concise natural-language
                    phrase in the root query's language; never emit slash-separated synonyms, OR expressions,
                    explanations, or a query-variant list. Change search_query only when the user explicitly
                    refines the concept, metric, geography, or requested measure.
                    For a genuinely different economic topic, use relation=new_topic, operation=search,
                    action=new_search and preserve_concept=false. Never replace an explicit concept with a
                    related concept. Do not decide from isolated keywords; use the full conversation.
                    Use operation=research, action=annotate_web_events when the user asks to find
                    historical events, crises or regimes on the internet and mark them in the current graph.
                    Use operation=research, action=discover_external_series only when the user explicitly
                    asks for missing data outside the internal catalog. In both cases search_query is the
                    self-contained research question. Ordinary data lookup remains operation=search.
                    For explain, compare, compute, compose or add_to_chart, select the exact required rows
                    from available_series and return their opaque ref_id values in series_ref_ids. Never invent
                    an id and never select rows merely because they rank first. Select only series explicitly
                    requested or necessary for the requested operation. Compare/compute/compose requires at
                    least two ids; add_to_chart and explain_indicator require at least one. For answer and search
                    operations return an empty series_ref_ids array.
                    If any series required by the user's requested graph or calculation is absent from
                    available_series, do not claim that the action is ready and do not emit a partial compose
                    plan. Use operation=search, action=refine_search, preserve_concept=true and a self-contained
                    search_query that retrieves the missing series while retaining the requested context.
                    """;
            JsonNode response = openAiClient.chatCompletion(systemPrompt, objectMapper.writeValueAsString(context));
            JsonNode content = response.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.asText().isBlank()) {
                return fallbackIntent(message, "empty_llm_response");
            }
            String text = content.asText().trim();
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return fallbackIntent(message, "invalid_llm_json");
            }
            JsonNode parsed = objectMapper.readTree(text.substring(start, end + 1));
            CatalogFollowupPlan plan = CatalogFollowupPlan.fromLlmJson(parsed);
            if (plan == null) {
                String repairPrompt = objectMapper.writeValueAsString(context)
                        + "\nThe previous planner output was invalid:\n"
                        + truncate(text, 2500)
                        + "\nReturn one corrected JSON object matching the schema. For data actions use only exact "
                        + "available_series ref_id values; if a required series is absent, plan a refine_search.";
                JsonNode repairedResponse = openAiClient.chatCompletion(systemPrompt, repairPrompt);
                String repairedText = repairedResponse.path("choices")
                        .path(0)
                        .path("message")
                        .path("content")
                        .asText("")
                        .trim();
                int repairedStart = repairedText.indexOf('{');
                int repairedEnd = repairedText.lastIndexOf('}');
                if (repairedStart >= 0 && repairedEnd > repairedStart) {
                    plan = CatalogFollowupPlan.fromLlmJson(
                            objectMapper.readTree(repairedText.substring(repairedStart, repairedEnd + 1)));
                }
            }
            return plan != null ? intentResponse(plan) : fallbackIntent(message, "invalid_llm_plan");
        } catch (Exception ex) {
            log.warn("results-intent LLM failed: {}", ex.getMessage());
            return fallbackIntent(message, ex.getClass().getSimpleName());
        }
    }

    private static Map<String, Object> fallbackIntent(String message, String reason) {
        if (CatalogFollowupQuerySupport.isSearchRefinementRequest(message)) {
            CatalogFollowupQuerySupport.SourceRequest request = CatalogFollowupQuerySupport.sourceRequest(message);
            String mode = !request.includedSources().isEmpty()
                    ? "include"
                    : request.alternativesRequested()
                            ? "alternatives"
                            : !request.excludedSources().isEmpty() ? "exclude" : "keep";
            String action = request.alternativesRequested() || !request.excludedSources().isEmpty()
                    ? "find_alternatives"
                    : "refine_search";
            CatalogFollowupPlan plan = new CatalogFollowupPlan(
                    "same_topic",
                    "search",
                    action,
                    true,
                    message,
                    new CatalogFollowupPlan.SourceConstraints(
                            mode, request.includedSources(), request.excludedSources()),
                    0.0,
                    reason,
                    "registry_fallback");
            return intentResponse(plan);
        }
        return Map.of(
                "ok", true,
                "intent", "unknown",
                "action_hint", "",
                "source", "fallback",
                "reason", reason);
    }

    private static Map<String, Object> intentResponse(CatalogFollowupPlan plan) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("intent", plan.compatibilityIntent());
        out.put("action_hint", plan.action());
        out.put("confidence", plan.confidence());
        out.put("reason_cz", plan.reasonCz());
        out.put("source", plan.routingSource());
        out.put("followup_plan", plan.toMap());
        return out;
    }

    private String askEconomist(String message, List<Map<String, Object>> rows, List<Map<String, Object>> history) {
        if (rows.isEmpty()) {
            return fallbackResultsChatAnswer(message, List.of());
        }
        try {
            // Dřív dostal model jen "název [zdroj / set_id]" a odpovídal proto povrchně. Přidáme metadata,
            // která řádky už nesou (jednotka, frekvence, území, popis), aby AI nad výsledky hledání uměla
            // doporučit konkrétní řadu a upozornit na rozdíly bránící srovnání.
            StringBuilder seriesBlock = new StringBuilder();
            for (Map<String, Object> row : rows.stream().limit(12).toList()) {
                seriesBlock.append("- ").append(stringValue(row, "title", "set_id", ""));
                String source = stringValue(row, "source_type", "catalog_id", "");
                String setId = stringValue(row, "set_id", "");
                seriesBlock.append(" [").append(source);
                if (!setId.isBlank()) {
                    seriesBlock.append(" / ").append(setId);
                }
                seriesBlock.append("]");
                List<String> attrs = new ArrayList<>();
                String unit = stringValue(row, "unit", "");
                if (!unit.isBlank()) {
                    attrs.add("jednotka " + unit);
                }
                String freq = stringValue(row, "frequency", "");
                if (!freq.isBlank()) {
                    attrs.add("frekvence " + freq);
                }
                String territory = stringValue(row, "territory", "geo", "");
                if (!territory.isBlank()) {
                    attrs.add("území " + territory);
                }
                if (!attrs.isEmpty()) {
                    seriesBlock.append(" (").append(String.join(", ", attrs)).append(")");
                }
                String desc = stringValue(row, "description", "");
                if (!desc.isBlank()) {
                    seriesBlock.append(" — ").append(truncate(desc, 180));
                }
                seriesBlock.append("\n");
            }
            StringBuilder historyBlock = new StringBuilder();
            for (Map<String, Object> msg : history.stream().limit(8).toList()) {
                historyBlock
                        .append(stringValue(msg, "role", "user"))
                        .append(": ")
                        .append(stringValue(msg, "content", ""))
                        .append("\n");
            }
            String systemPrompt =
                    """
                    Jsi ekonomický analytik, který uživateli pomáhá zorientovat se v NALEZENÝCH datových řadách.
                    Pracuj VÝHRADNĚ s uvedenými řadami (názvy, zdroje, jednotky, frekvence, území, popisy) —
                    nevymýšlej si další řady ani konkrétní čísla, která v seznamu nejsou. Odpovídej stručně a
                    konkrétně česky a podle smyslu dotazu:
                    - doporuč 1–3 nejvhodnější řady a krátce proč (odkazuj se na ně názvem),
                    - upozorni na rozdíly bránící srovnání (jiná jednotka, frekvence, území, zdroj/metodika),
                    - když žádná nalezená řada dotaz nepokrývá, řekni to a navrhni, jak upřesnit hledání.
                    Nespouštěj nové hledání.
                    """;
            String userPrompt =
                    "Nalezené řady:\n" + seriesBlock + "\nHistorie:\n" + historyBlock + "\nDotaz: " + message;
            JsonNode response = openAiClient.chatCompletion(systemPrompt, userPrompt);
            String answer = response.path("choices").path(0).path("message").path("content").asText("").trim();
            if (answer.isBlank()) {
                return fallbackResultsChatAnswer(message, rows);
            }
            return answer;
        } catch (Exception ex) {
            log.warn("results-chat LLM failed: {}", ex.getMessage());
            return fallbackResultsChatAnswer(message, rows);
        }
    }

    private static String fallbackResultsChatAnswer(String message, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return "Zatím nemám nalezené řady, ke kterým bych mohl navázat. Zkuste nejdřív spustit AI návrh řad.";
        }
        return "K vašemu dotazu „"
                + message
                + "“ doporučuji vybrat 2–4 nejrelevantnější nalezené řady a ověřit, zda měří stejný koncept, než je porovnáte v grafu.";
    }

    private Map<String, Object> loadConversation(String cid, UserEntity user) {
        cleanExpired();
        Map<String, Object> doc = conversations.get(cid);
        if (doc == null) {
            return null;
        }
        // doc.get("user_id") is explicitly null for anonymous bootstraps; getOrDefault() only falls back
        // when the key is *absent*, so String.valueOf(null) previously produced the literal "null" string
        // (non-blank) and permanently locked anonymous users out of their own conversation.
        Object rawOwner = doc.get("user_id");
        String owner = rawOwner == null ? "" : String.valueOf(rawOwner).trim();
        if (!owner.isBlank() && (user == null || !owner.equals(user.getId()))) {
            return null;
        }
        return doc;
    }

    /**
     * Recreates an expired in-memory conversation from the current result context supplied by the
     * client. This keeps follow-ups usable across development restarts and normal TTL expiry without
     * weakening ownership checks for conversations that still exist.
     */
    private Map<String, Object> recoverConversation(Map<String, Object> payload, UserEntity user) {
        Map<String, Object> recovery = normalizeMap(payload.get("recovery_context"));
        List<Map<String, Object>> selectedRefs = normalizeRows(payload.get("selected_series_refs"));
        List<Map<String, Object>> availableRefs = normalizeRows(payload.get("available_series_refs"));
        List<Map<String, Object>> foundSummary = normalizeRows(recovery.get("found_summary"));
        if (!availableRefs.isEmpty()) {
            foundSummary = CatalogFollowupSeriesResolver.merge(availableRefs, foundSummary);
        }
        if (foundSummary.isEmpty()) {
            foundSummary = selectedRefs;
        }
        if (foundSummary.isEmpty()) {
            return null;
        }
        if (selectedRefs.isEmpty()) {
            selectedRefs = foundSummary;
        }

        String cid = UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        String rootQuery = stringValue(recovery, "root_query", "");

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("mode", recovery.getOrDefault("mode", "multi"));
        context.put("sources", recovery.getOrDefault("sources", List.of()));
        context.put("use_ai", recovery.getOrDefault("use_ai", true));
        context.put("root_query", rootQuery);
        context.put("last_user_message", rootQuery);
        context.put("last_search_query", rootQuery);
        context.put("current_focus_query", rootQuery);
        context.put("last_query", rootQuery);
        context.put("selected_series_refs", new ArrayList<>(selectedRefs));
        context.put("available_series_refs", new ArrayList<>(foundSummary));
        context.put("found_series_summary", new ArrayList<>(foundSummary));
        context.put("topic_anchor", CatalogFollowupQuerySupport.topicAnchorFromFoundSummary(foundSummary));

        List<Map<String, Object>> messages = new ArrayList<>();
        if (!rootQuery.isBlank()) {
            messages.add(message("user", rootQuery));
        }

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("conversation_id", cid);
        doc.put("user_id", user != null ? user.getId() : null);
        doc.put("messages", messages);
        doc.put("context", context);
        doc.put("created_at", now.toEpochMilli() / 1000.0);
        doc.put("updated_at", now.toEpochMilli() / 1000.0);
        doc.put("expires_at", now.toEpochMilli() / 1000.0 + TEMP_CONV_TTL_SEC);
        conversations.put(cid, doc);
        return doc;
    }

    private void cleanExpired() {
        double now = Instant.now().toEpochMilli() / 1000.0;
        conversations.entrySet().removeIf(entry -> {
            Object expires = entry.getValue().get("expires_at");
            if (expires instanceof Number n) {
                return n.doubleValue() <= now;
            }
            return false;
        });
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> summarizeFromContext(Map<String, Object> doc) {
        Map<String, Object> context = (Map<String, Object>) doc.getOrDefault("context", Map.of());
        Object raw = context.get("found_series_summary");
        if (raw instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }

    private static List<Map<String, Object>> summarizeFoundSeries(Map<String, Object> deepResult) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String key : List.of("verified", "possible")) {
            Object raw = deepResult.get(key);
            if (!(raw instanceof List<?> list)) {
                continue;
            }
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> row)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) row;
                String source = String.valueOf(map.getOrDefault("source_type", map.getOrDefault("catalog_id", "")))
                        .trim()
                        .toLowerCase(Locale.ROOT);
                String setId = String.valueOf(map.getOrDefault("set_id", map.getOrDefault("series_id", ""))).trim();
                if (source.isBlank() || setId.isBlank()) {
                    continue;
                }
                Map<String, Object> summary = new LinkedHashMap<>(map);
                summary.put("source_type", source);
                summary.put("catalog_id", source);
                summary.put("set_id", setId);
                summary.put("title", String.valueOf(map.getOrDefault("title", map.getOrDefault("name", setId))));
                summary.put("ref_id", CatalogFollowupSeriesResolver.refId(summary));
                out.add(summary);
                if (out.size() >= 24) {
                    return out;
                }
            }
        }
        return out;
    }

    private static List<Map<String, Object>> summarizeFoundSeriesForContext(Map<String, Object> deepResult) {
        List<Map<String, Object>> out = summarizeFoundSeries(deepResult);
        if (!out.isEmpty()) {
            return out;
        }
        return summarizeRowsFromKey(deepResult, "discarded_candidates", 12);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> summarizeRowsFromKey(
            Map<String, Object> deepResult, String key, int limit) {
        Object raw = deepResult.get(key);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> row)) {
                continue;
            }
            Map<String, Object> map = (Map<String, Object>) row;
            String source = String.valueOf(map.getOrDefault("source_type", map.getOrDefault("catalog_id", "")))
                    .trim()
                    .toLowerCase(Locale.ROOT);
            String setId = String.valueOf(map.getOrDefault("set_id", map.getOrDefault("series_id", ""))).trim();
            if (source.isBlank() || setId.isBlank()) {
                continue;
            }
            out.add(Map.of(
                    "source_type", source,
                    "catalog_id", source,
                    "set_id", setId,
                    "title", String.valueOf(map.getOrDefault("title", map.getOrDefault("name", setId))),
                    "visibility", "discarded_candidate"));
            if (out.size() >= limit) {
                return out;
            }
        }
        return out;
    }

    private static List<Map<String, Object>> extractSeriesRefs(Map<String, Object> deepResult) {
        return summarizeFoundSeries(deepResult);
    }

    private static Map<String, Object> plannerSeriesRow(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ref_id", CatalogFollowupSeriesResolver.refId(row));
        out.put("title", stringValue(row, "title", ""));
        out.put("source", stringValue(row, "source_type", "catalog_id", ""));
        out.put("set_id", stringValue(row, "set_id", ""));
        out.put("territory", stringValue(row, "territory", ""));
        out.put("description", stringValue(row, "description", ""));
        out.put("unit", stringValue(row, "unit", ""));
        out.put("frequency", stringValue(row, "frequency", ""));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> normalizeRows(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                out.add((Map<String, Object>) map);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalizeMap(Object raw) {
        return raw instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : Map.of();
    }

    private static Map<String, Object> message(String role, String content) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        row.put("role", role);
        row.put("content", content == null ? "" : content.trim());
        row.put("ts", Instant.now().getEpochSecond());
        return row;
    }

    private CatalogFollowupActionHandler.FollowupActionResult dispatchComputeAction(
            String actionHint, String message, List<Map<String, Object>> selectedRefs, UserEntity user) {
        if (actionHint == null || actionHint.isBlank()) {
            return null;
        }
        return switch (actionHint) {
            case "explain_indicator",
                    "compute_ratio",
                    "compute_difference",
                    "compute_percent_change",
                    "compose_multi_chart",
                    "compare_selected",
                    "add_to_chart" ->
                    actionHandler.handle(
                            actionHint, message, selectedRefs, user != null ? user.getId() : null);
            default -> null;
        };
    }

    private static boolean looksLikeRefineSearch(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("hledej")
                || lower.contains("najdi")
                || lower.contains("zpřesni")
                || lower.contains("zuz")
                || lower.contains("nové téma");
    }

    private static List<String> allowedFollowups() {
        return List.of(
                "explain_indicator",
                "find_alternatives",
                "refine_search",
                "compute_ratio",
                "compute_difference",
                "compute_percent_change",
                "compose_multi_chart",
                "compare_selected",
                "add_to_chart",
                "annotate_web_events",
                "discover_external_series");
    }

    private static Map<String, Object> researchContext(
            Map<String, Object> explicit,
            Map<String, Object> conversationContext,
            List<Map<String, Object>> availableRefs) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (explicit != null) {
            out.putAll(explicit);
        }
        if (stringValue(out, "title", "").isBlank()) {
            out.put("title", stringValue(conversationContext, "root_query", ""));
        }
        if (!(out.get("series_labels") instanceof List<?>)) {
            out.put("series_labels", availableRefs.stream()
                    .map(row -> stringValue(row, "title", ""))
                    .filter(title -> !title.isBlank())
                    .distinct()
                    .limit(12)
                    .toList());
        }
        if (!(out.get("sources") instanceof List<?>)) {
            out.put("sources", availableRefs.stream()
                    .map(row -> stringValue(row, "source_type", "catalog_id", ""))
                    .filter(source -> !source.isBlank())
                    .distinct()
                    .limit(12)
                    .toList());
        }
        return out;
    }

    private static String resolveFollowupQuery(
            CatalogFollowupPlan plan, String message, Map<String, Object> context) {
        if (plan == null || "registry_fallback".equals(plan.routingSource())) {
            return CatalogFollowupQuerySupport.buildContextualFollowupQuery(message, context);
        }
        return plan.searchQuery();
    }

    private static List<String> resolveFollowupSources(
            CatalogFollowupPlan plan, String message, Map<String, Object> context) {
        if (plan == null || "registry_fallback".equals(plan.routingSource())) {
            return resolveRegistryFallbackSources(message, context);
        }

        List<String> currentSources = contextSources(context);
        CatalogFollowupPlan.SourceConstraints constraints = plan.sourceConstraints();
        List<String> sources;
        switch (constraints.mode()) {
            case "include" -> sources = new ArrayList<>(constraints.include());
            case "exclude" -> {
                sources = new ArrayList<>(CatalogSourceRegistry.FOLLOWUP_DEFAULT_SOURCE_POOL);
                sources.removeAll(constraints.exclude());
            }
            case "alternatives" -> {
                sources = constraints.include().isEmpty()
                        ? new ArrayList<>(CatalogSourceRegistry.FOLLOWUP_DEFAULT_SOURCE_POOL)
                        : new ArrayList<>(constraints.include());
                if (constraints.exclude().isEmpty()) {
                    sources.removeAll(currentSources);
                } else {
                    sources.removeAll(constraints.exclude());
                }
            }
            case "keep" -> sources = currentSources.isEmpty()
                    ? new ArrayList<>(CatalogSourceRegistry.FOLLOWUP_DEFAULT_SOURCE_POOL)
                    : new ArrayList<>(currentSources);
            default -> throw new IllegalStateException("Validated follow-up plan contains an unknown source mode");
        }
        context.put("sources", sources);
        return sources;
    }

    private static List<String> resolveRegistryFallbackSources(String message, Map<String, Object> context) {
        CatalogFollowupQuerySupport.SourceRequest request = CatalogFollowupQuerySupport.sourceRequest(message);
        if (!request.includedSources().isEmpty()) {
            context.put("sources", request.includedSources());
            return request.includedSources();
        }
        List<String> currentSources = contextSources(context);
        List<String> sources = new ArrayList<>(currentSources);
        if (request.alternativesRequested() || !request.excludedSources().isEmpty()) {
            sources = new ArrayList<>(CatalogSourceRegistry.FOLLOWUP_DEFAULT_SOURCE_POOL);
            if (!request.excludedSources().isEmpty()) {
                sources.removeAll(request.excludedSources());
            } else {
                sources.removeAll(currentSources);
            }
            context.put("sources", sources);
            return sources;
        }
        if (sources.size() <= 3) {
            sources = new ArrayList<>(CatalogSourceRegistry.FOLLOWUP_DEFAULT_SOURCE_POOL);
        }
        return sources;
    }

    private static List<String> contextSources(Map<String, Object> context) {
        Object raw = context.get("sources");
        List<String> sources = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                String source = CatalogSourceRegistry.normalizeSearchSource(String.valueOf(item));
                if (!source.isBlank()
                        && CatalogSourceRegistry.FOLLOWUP_DEFAULT_SOURCE_POOL.contains(source)
                        && !sources.contains(source)) {
                    sources.add(source);
                }
            }
        }
        return sources;
    }

    private static void updateContextAfterRefine(
            Map<String, Object> context, String message, String effectiveQuery, Map<String, Object> deepResult) {
        String root = str(context.get("root_query"));
        if (root.isBlank()) {
            root = str(context.get("last_query"));
        }
        if (!root.isBlank()) {
            context.put("root_query", truncate(root, 500));
        }
        context.put("last_user_message", truncate(message, 500));
        context.put("last_search_query", truncate(effectiveQuery, 700));
        context.put("last_query", truncate(effectiveQuery, 700));
        context.put("current_focus_query", truncate(effectiveQuery, 500));
        List<Map<String, Object>> summary = summarizeFoundSeriesForContext(deepResult);
        if (!summary.isEmpty()) {
            List<Map<String, Object>> refs = extractSeriesRefs(deepResult);
            List<Map<String, Object>> previous = normalizeRows(context.get("available_series_refs"));
            List<Map<String, Object>> merged = CatalogFollowupSeriesResolver.merge(previous, refs);
            context.put("found_series_summary", merged);
            context.put("selected_series_refs", merged);
            context.put("available_series_refs", merged);
            String anchor = CatalogFollowupQuerySupport.topicAnchorFromFoundSummary(summary);
            if (!anchor.isBlank()) {
                context.put("topic_anchor", anchor);
            }
        }
    }

    private static String extractDeepSearchAssistantAnswer(Map<String, Object> deepResult) {
        Object aiLayer = deepResult.get("ai_result_layer");
        if (aiLayer instanceof Map<?, ?> layer) {
            String answer = str(layer.get("answer_cz"));
            if (!answer.isBlank()) {
                return answer;
            }
        }
        String answer = str(deepResult.get("answer"));
        if (!answer.isBlank()) {
            return answer;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> verified =
                deepResult.get("verified") instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> possible =
                deepResult.get("possible") instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
        if (!verified.isEmpty()) {
            return "Našla jsem upravené výsledky. Zkontrolujte prosím ověřené řady níže.";
        }
        if (!possible.isEmpty()) {
            return "Ověřenou řadu se mi k tomuto upřesnění najít nepodařilo, ale níže jsou možní kandidáti z katalogu k ručnímu výběru.";
        }
        return "K tomuto upřesnění jsem bohužel nenašla žádné odpovídající řady. Zkuste dotaz přeformulovat nebo upřesnit zemi a ukazatel.";
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    /**
     * Null-safe replacement for {@code String.valueOf(map.getOrDefault(key, fallback))}: unlike
     * {@code getOrDefault}, this also falls back when the key is present with an explicit {@code null}
     * value (e.g. JSON {@code "key": null} from a client payload), avoiding the literal "null" string.
     */
    private static String stringValue(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value != null ? String.valueOf(value).trim() : fallback;
    }

    /** Same as {@link #stringValue(Map, String, String)} but with a secondary key fallback. */
    private static String stringValue(Map<String, Object> map, String primaryKey, String secondaryKey, String fallback) {
        Object primary = map.get(primaryKey);
        if (primary != null) {
            return String.valueOf(primary).trim();
        }
        Object secondary = map.get(secondaryKey);
        return secondary != null ? String.valueOf(secondary).trim() : fallback;
    }

    private static String truncate(String text, int maxLen) {
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen).trim();
    }
}
