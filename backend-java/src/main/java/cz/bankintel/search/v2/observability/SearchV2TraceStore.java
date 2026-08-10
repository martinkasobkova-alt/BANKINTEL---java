package cz.bankintel.search.v2.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class SearchV2TraceStore {

    private static final int MAX_TRACES = 200;

    private final Map<String, Map<String, Object>> traces = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> eldest) {
            return size() > MAX_TRACES;
        }
    };

    public synchronized void save(SearchV2Trace trace) {
        traces.put(trace.traceId(), trace.snapshot());
    }

    public synchronized Optional<Map<String, Object>> get(String traceId) {
        return Optional.ofNullable(traces.get(traceId));
    }
}
