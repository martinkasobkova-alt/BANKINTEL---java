package cz.bankintel.explore.manager.fetch;

import cz.bankintel.search.CatalogSourceRegistry;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Routes summarize refs to domain-specific manager fetchers before generic catalog preview. */
@Component
public class ManagerFetchRegistry {

    private final List<ManagerSegmentFetch> fetchers;

    public ManagerFetchRegistry(List<ManagerSegmentFetch> fetchers) {
        this.fetchers = fetchers;
    }

    public Optional<List<Map<String, Object>>> tryFetch(Map<String, Object> ref, String country) {
        if (ref == null || ref.isEmpty()) {
            return Optional.empty();
        }
        String sourceType = CatalogSourceRegistry.normalizeSearchSource(str(ref.get("source_type")));
        if (sourceType.isBlank()) {
            return Optional.empty();
        }
        ManagerSegmentFetch fetcher = fetchers.stream().filter(f -> f.supports(sourceType)).findFirst().orElse(null);
        if (fetcher == null) {
            return Optional.empty();
        }
        Map<String, Object> context = new java.util.LinkedHashMap<>(ref);
        if (!str(country).isBlank()) {
            context.putIfAbsent("context_country", country.toUpperCase(Locale.ROOT));
        }
        String query = str(ref.get("set_id"));
        if (query.isBlank()) {
            query = str(ref.get("series_id"));
        }
        List<Map<String, Object>> rows = fetcher.fetchSegmentData(query, context);
        if (ManagerMirrorFetchSupport.isMirrorUnavailable(rows)) {
            return Optional.of(rows);
        }
        return rows != null && rows.size() >= 2 ? Optional.of(rows) : Optional.empty();
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }
}
