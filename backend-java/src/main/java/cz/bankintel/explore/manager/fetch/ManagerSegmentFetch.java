package cz.bankintel.explore.manager.fetch;

import java.util.List;
import java.util.Map;

/**
 * Specialized Manager Explorer segment fetch — port Python {@code *_manager_fetch.py}.
 *
 * <p>Each implementation reads local mirror CSV/JSON (build-time import) or falls back to HTTP stub.
 */
public interface ManagerSegmentFetch {

    /** Whether this fetcher handles the normalized source type (e.g. {@code acea_mirror}, {@code oecd4}). */
    boolean supports(String sourceType);

    /**
     * Fetch observation rows for a manager segment query.
     *
     * @param query segment or series identifier (often {@code set_id} / {@code series_id})
     * @param context ref map: {@code source_type}, {@code set_id}, {@code query_params}, {@code geo}, …
     * @return time-series rows with at least {@code date}/{@code period} and {@code value}
     */
    List<Map<String, Object>> fetchSegmentData(String query, Map<String, Object> context);
}
