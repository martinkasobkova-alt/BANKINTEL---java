package cz.bankintel.search.v2.observability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SearchV2Trace {

    private final String traceId = UUID.randomUUID().toString();
    private final long startedAtMs = System.currentTimeMillis();
    private final Map<String, Object> data = new LinkedHashMap<>();
    private final Map<String, Object> timings = new LinkedHashMap<>();
    private final List<Map<String, Object>> events = new ArrayList<>();

    public SearchV2Trace(String query) {
        data.put("trace_id", traceId);
        data.put("query", query);
        data.put("started_at_ms", startedAtMs);
    }

    public String traceId() {
        return traceId;
    }

    public void put(String key, Object value) {
        data.put(key, value);
    }

    public void timing(String key, long valueMs) {
        timings.put(key, valueMs);
    }

    public void event(String name, Object payload) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("name", name);
        event.put("at_ms", System.currentTimeMillis() - startedAtMs);
        event.put("payload", payload);
        events.add(event);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>(data);
        out.put("timings", timings);
        out.put("events", events);
        out.put("total_ms", System.currentTimeMillis() - startedAtMs);
        return out;
    }
}
