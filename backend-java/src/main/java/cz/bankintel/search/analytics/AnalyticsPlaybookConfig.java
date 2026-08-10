package cz.bankintel.search.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads {@code catalog/analytics_playbooks.json} — domain -> ordered calculation-type list +
 * benchmark-group hint. Domain matching is delegated to {@link
 * cz.bankintel.search.forecast.ForecastPredictorConfig}; this file only supplies which
 * deterministic calculations to run once a domain is known.
 */
public final class AnalyticsPlaybookConfig {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsPlaybookConfig.class);
    private static final String RESOURCE_PATH = "/catalog/analytics_playbooks.json";

    public record Playbook(
            List<String> calculationTypes, String benchmarkGroup, String notes) {}

    private static final AnalyticsPlaybookConfig INSTANCE = new AnalyticsPlaybookConfig();

    private final List<String> defaultCalculationTypes;
    private final Map<String, Playbook> domains;
    private final Map<String, List<String>> geoGroups;

    private AnalyticsPlaybookConfig() {
        Loaded loaded = load();
        this.defaultCalculationTypes = loaded.defaultCalculationTypes();
        this.domains = loaded.domains();
        this.geoGroups = loaded.geoGroups();
    }

    public static AnalyticsPlaybookConfig get() {
        return INSTANCE;
    }

    public List<String> defaultCalculationTypes() {
        return defaultCalculationTypes;
    }

    public Optional<Playbook> playbookForDomain(String domainKey) {
        if (domainKey == null || domainKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(domains.get(domainKey));
    }

    public List<String> geoGroupMembers(String groupKey) {
        if (groupKey == null || groupKey.isBlank()) {
            return List.of();
        }
        return geoGroups.getOrDefault(groupKey, List.of());
    }

    private record Loaded(List<String> defaultCalculationTypes, Map<String, Playbook> domains, Map<String, List<String>> geoGroups) {}

    private static Loaded load() {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = AnalyticsPlaybookConfig.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                log.warn("analytics_playbooks.json not found on classpath ({})", RESOURCE_PATH);
                return new Loaded(List.of("basic_metrics", "trend", "anomalies"), Map.of(), Map.of());
            }
            JsonNode root = mapper.readTree(in);
            List<String> defaults = new ArrayList<>();
            root.path("default_calculation_types").forEach(n -> defaults.add(n.asText("")));
            defaults.removeIf(String::isBlank);

            Map<String, Playbook> domainMap = new LinkedHashMap<>();
            root.path("domains").fields().forEachRemaining(entry -> {
                JsonNode node = entry.getValue();
                List<String> calcTypes = new ArrayList<>();
                node.path("calculation_types").forEach(n -> calcTypes.add(n.asText("")));
                calcTypes.removeIf(String::isBlank);
                String benchmarkGroup = node.path("benchmark_group").isNull() || node.path("benchmark_group").isMissingNode()
                        ? null
                        : node.path("benchmark_group").asText(null);
                String notes = node.path("notes").asText("");
                domainMap.put(entry.getKey(), new Playbook(calcTypes, benchmarkGroup, notes));
            });

            Map<String, List<String>> geoMap = new LinkedHashMap<>();
            root.path("geo_groups").fields().forEachRemaining(entry -> {
                List<String> members = new ArrayList<>();
                entry.getValue().forEach(n -> members.add(n.asText("")));
                members.removeIf(String::isBlank);
                geoMap.put(entry.getKey(), members);
            });
            return new Loaded(defaults, domainMap, geoMap);
        } catch (Exception ex) {
            log.warn("Failed to load analytics_playbooks.json: {}", ex.getMessage());
            return new Loaded(List.of("basic_metrics", "trend", "anomalies"), Map.of(), Map.of());
        }
    }
}
