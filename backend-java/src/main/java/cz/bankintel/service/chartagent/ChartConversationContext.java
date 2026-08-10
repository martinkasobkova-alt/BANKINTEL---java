package cz.bankintel.service.chartagent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ChartConversationContext {

    private static final int MAX_HISTORY_MESSAGES = 8;
    private static final int MAX_STATE_TURNS = 6;
    private static final int MAX_ACTIONS_PER_TURN = 8;
    private static final int MAX_ACTIVE_ANNOTATIONS = 24;

    private ChartConversationContext() {}

    @SuppressWarnings("unchecked")
    static List<Map<String, String>> historyBrief(Object historyObj) {
        if (!(historyObj instanceof List<?> history)) {
            return List.of();
        }
        List<Map<String, String>> out = new ArrayList<>();
        int start = Math.max(0, history.size() - MAX_HISTORY_MESSAGES);
        for (int i = start; i < history.size(); i++) {
            if (!(history.get(i) instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> message = (Map<String, Object>) raw;
            String role = ChartContractParser.str(message.get("role")).toLowerCase();
            if (!"user".equals(role) && !"assistant".equals(role)) {
                continue;
            }
            String content = boundedText(message.get("content"), 900);
            if (!content.isBlank()) {
                out.add(Map.of("role", role, "content", content));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> stateBrief(Object stateObj) {
        if (!(stateObj instanceof List<?> state)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        int start = Math.max(0, state.size() - MAX_STATE_TURNS);
        for (int i = start; i < state.size(); i++) {
            if (!(state.get(i) instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> turn = (Map<String, Object>) raw;
            Map<String, Object> brief = new LinkedHashMap<>();
            copyText(turn, brief, "question", 500);
            copyText(turn, brief, "answer_cz", 700);
            copyText(turn, brief, "research_mode", 80);
            brief.put("chart_actions", actionBriefs(turn.get("chart_actions"), MAX_ACTIONS_PER_TURN));
            if (!ChartContractParser.str(brief.get("question")).isBlank()) {
                out.add(brief);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> activeAnnotations(Map<String, Object> contract) {
        if (contract == null || !(contract.get("active_annotations") instanceof List<?> annotations)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        int start = Math.max(0, annotations.size() - MAX_ACTIVE_ANNOTATIONS);
        for (int i = start; i < annotations.size(); i++) {
            if (!(annotations.get(i) instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> annotation = (Map<String, Object>) raw;
            Map<String, Object> brief = new LinkedHashMap<>();
            copyText(annotation, brief, "label", 180);
            copyText(annotation, brief, "from", 32);
            copyText(annotation, brief, "to", 32);
            copyText(annotation, brief, "description_cz", 360);
            if (!brief.isEmpty()) {
                out.add(brief);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> actionBriefs(Object actionsObj, int limit) {
        if (!(actionsObj instanceof List<?> actions)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : actions.stream().limit(limit).toList()) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> action = (Map<String, Object>) raw;
            Map<String, Object> brief = new LinkedHashMap<>();
            copyText(action, brief, "type", 80);
            copyText(action, brief, "label", 180);
            copyText(action, brief, "from", 32);
            copyText(action, brief, "to", 32);
            copyText(action, brief, "description_cz", 360);
            copyText(action, brief, "series_id", 160);
            if (!brief.isEmpty()) {
                out.add(brief);
            }
        }
        return out;
    }

    private static void copyText(Map<String, Object> source, Map<String, Object> target, String key, int maxLength) {
        String value = boundedText(source.get(key), maxLength);
        if (!value.isBlank()) {
            target.put(key, value);
        }
    }

    private static String boundedText(Object value, int maxLength) {
        String text = ChartContractParser.str(value);
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }
}
