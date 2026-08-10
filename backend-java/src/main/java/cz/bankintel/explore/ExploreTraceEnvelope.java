package cz.bankintel.explore;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Shared terminal observability contract for Manager Explorer REST and SSE responses. */
public final class ExploreTraceEnvelope {

    private ExploreTraceEnvelope() {}

    public static Map<String, Object> forRest(Map<String, Object> body) {
        Map<String, Object> sourceStatuses = map(body, "source_terminal_statuses");
        int runCount = hasDiscoveryEvidence(body) ? 1 : 0;
        return enrich(
                body,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                runCount,
                sourceStatuses,
                terminalStatus(body));
    }

    public static Map<String, Object> enrich(
            Map<String, Object> body,
            String requestId,
            String discoveryRunId,
            int fullDiscoveryRunCount,
            Map<String, ?> sourceStatuses,
            String terminalStatus) {
        Map<String, Object> input = body == null ? Map.of() : body;
        Map<String, Object> profile = map(input, "performance_profile");
        Map<String, Object> enriched = new LinkedHashMap<>(input);

        enriched.put("request_id", requestId);
        enriched.put("discovery_run_id", discoveryRunId);
        enriched.put("full_discovery_run_count", Math.max(0, fullDiscoveryRunCount));
        enriched.put("cache_hit", bool(input.get("cache_hit")));
        enriched.put("fallback_reason", firstNonNull(
                input.get("discovery_fallback_reason"), profile.get("fallback_reason")));
        enriched.put("serving_time_ms", number(input.get("serving_time_ms"), 0L));
        enriched.put("cached_compute_time_ms", number(input.get("cached_compute_time_ms"), 0L));
        enriched.put("source_routing", sourceRouting(profile, sourceStatuses));
        enriched.put("candidate_count", candidateCount(input, profile));
        enriched.put("preview_count", previewCount(profile));
        enriched.put("validator_outcome", validatorOutcome(input, profile));
        enriched.put("terminal_status", terminalStatus);

        if (sourceStatuses != null && !sourceStatuses.isEmpty()) {
            enriched.put("source_terminal_statuses", new LinkedHashMap<>(sourceStatuses));
        }
        return enriched;
    }

    private static boolean hasDiscoveryEvidence(Map<String, Object> body) {
        return body != null
                && (body.containsKey("total_candidates")
                        || body.containsKey("performance_profile")
                        || body.containsKey("sector_indicators") && bool(body.get("ok")));
    }

    private static String terminalStatus(Map<String, Object> body) {
        return body != null && Boolean.FALSE.equals(body.get("ok")) ? "error" : "completed";
    }

    private static Object sourceRouting(Map<String, Object> profile, Map<String, ?> statuses) {
        Object requested = profile.get("sources_requested");
        if (requested instanceof Collection<?> collection && !collection.isEmpty()) {
            return List.copyOf(collection);
        }
        Object fallback = profile.get("fallback_source_routing");
        if (fallback instanceof Collection<?> collection && !collection.isEmpty()) {
            return List.copyOf(collection);
        }
        Object sourceProfiles = profile.get("source_statuses");
        if (sourceProfiles instanceof Collection<?> collection) {
            List<String> sources = collection.stream()
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .map(source -> source.get("source"))
                    .filter(value -> value != null && !String.valueOf(value).isBlank())
                    .map(String::valueOf)
                    .distinct()
                    .toList();
            if (!sources.isEmpty()) {
                return sources;
            }
        }
        return statuses == null ? List.of() : List.copyOf(statuses.keySet());
    }

    private static long candidateCount(Map<String, Object> body, Map<String, Object> profile) {
        Object value = firstNonNull(
                body.get("total_candidates"),
                profile.get("candidate_count_resolver_output"),
                profile.get("candidate_count_retrieved"),
                profile.get("fallback_candidate_count"));
        return number(value, 0L);
    }

    private static long previewCount(Map<String, Object> profile) {
        Object value = firstNonNull(
                profile.get("preview_count"),
                profile.get("fallback_preview_count"),
                nestedCount(profile.get("preview_initial")),
                nestedCount(profile.get("preview_retry")));
        return number(value, 0L);
    }

    private static Object nestedCount(Object value) {
        if (value instanceof Map<?, ?> map) {
            Object items = map.get("preview_items");
            return firstNonNull(
                    map.get("count"),
                    map.get("attempted"),
                    map.get("preview_count"),
                    map.get("items"),
                    items instanceof Collection<?> collection ? collection.size() : null);
        }
        if (value instanceof Collection<?> collection) {
            return collection.size();
        }
        return null;
    }

    private static String validatorOutcome(Map<String, Object> body, Map<String, Object> profile) {
        Object explicit = firstNonNull(profile.get("validator_outcome"), profile.get("fallback_validator_outcome"));
        if (explicit != null && !String.valueOf(explicit).isBlank()) {
            return String.valueOf(explicit);
        }
        long verified = number(profile.get("verified_count"), count(body.get("verified_indicators")));
        long possible = number(profile.get("possible_count"), count(body.get("possible_indicators")));
        if (verified > 0) {
            return "verified";
        }
        if (possible > 0) {
            return "possible_only";
        }
        return previewCount(profile) > 0 ? "rejected" : "not_run";
    }

    private static long count(Object value) {
        return value instanceof Collection<?> collection ? collection.size() : 0L;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> source, String key) {
        if (source != null && source.get(key) instanceof Map<?, ?> value) {
            return (Map<String, Object>) value;
        }
        return Map.of();
    }

    private static Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean bool && bool;
    }

    private static long number(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                // A trace must remain available even when an upstream diagnostic value is malformed.
            }
        }
        return fallback;
    }
}
