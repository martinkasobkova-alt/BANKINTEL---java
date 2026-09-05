package cz.bankintel.search.v2.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.CatalogSourceRegistry;
import cz.bankintel.search.v2.schema.ExactEntityResolution;
import cz.bankintel.search.v2.schema.SourceRoutingDecision;
import cz.bankintel.search.v2.ontology.SearchV2ConceptRegistry.ConceptDefinition;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class SearchV2SourceCapabilityRegistry {

    private static final String RESOURCE = "/search_v2/source_capability_registry.json";

    private final Map<String, SourceCapability> capabilities;

    public SearchV2SourceCapabilityRegistry(ObjectMapper objectMapper) {
        this.capabilities = load(objectMapper);
    }

    /**
     * Every source this deployment's capability registry declares - the generic "which sources
     * exist to check" list for callers that must try every known source without hardcoding a
     * duplicate copy of the source id list (e.g. {@code ExactEntityResolver}'s catalog
     * verification for code-like queries).
     */
    public Set<String> knownSources() {
        return capabilities.keySet();
    }

    public SourceRoutingDecision route(ExactEntityResolution resolution) {
        if (resolution == null || resolution.catalogFamily().isBlank()) {
            return SourceRoutingDecision.empty();
        }
        Set<String> families = new LinkedHashSet<>();
        families.add(resolution.catalogFamily());
        Set<String> preferred = new LinkedHashSet<>();
        Map<String, String> reasons = new LinkedHashMap<>();
        for (String source : resolution.preferredSources() == null ? List.<String>of() : resolution.preferredSources()) {
            String normalized = CatalogSourceRegistry.normalizeSearchSource(source);
            SourceCapability capability = capabilities.get(normalized);
            if (capability != null && supports(capability, resolution.entityType(), resolution.catalogFamily())) {
                preferred.add(normalized);
                reasons.put(normalized, "preferred by exact entity registry and compatible with "
                        + resolution.entityType() + "/" + resolution.catalogFamily());
            }
        }
        if (preferred.isEmpty()) {
            for (SourceCapability capability : capabilities.values()) {
                if (supports(capability, resolution.entityType(), resolution.catalogFamily())) {
                    preferred.add(capability.source());
                    reasons.putIfAbsent(capability.source(), "source capability matches "
                            + resolution.entityType() + "/" + resolution.catalogFamily());
                }
            }
        }
        return new SourceRoutingDecision(
                new ArrayList<>(families),
                new ArrayList<>(preferred),
                List.of(),
                reasons);
    }

    /**
     * Deterministic fallback tier for {@link cz.bankintel.search.v2.planner.SearchV2QueryPlanner}: when
     * neither exact-entity nor concept-registry routing fires (the concept ontology has no named entry
     * for the query's phrasing), a resolved {@code metric_intent} (debt/profitability/cost/assets - see
     * {@code SearchV2MetricIntentRegistry}) is still structural evidence that the query wants {@code
     * financial_ratio}-typed data, regardless of which specific metric or institutional sector it names.
     * Reuses this registry's own existing {@code entity_types} capability data - no new per-metric or
     * per-sector source mapping is introduced.
     */
    public SourceRoutingDecision routeByEntityType(String entityType) {
        String normalized = entityType == null ? "" : entityType.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return SourceRoutingDecision.empty();
        }
        Set<String> preferred = new LinkedHashSet<>();
        Map<String, String> reasons = new LinkedHashMap<>();
        for (SourceCapability capability : capabilities.values()) {
            if (capability.entityTypes().contains(normalized)) {
                preferred.add(capability.source());
                reasons.put(capability.source(), "source capability supports entity type " + normalized);
            }
        }
        return new SourceRoutingDecision(List.of(), new ArrayList<>(preferred), List.of(), reasons);
    }

    public SourceRoutingDecision routeConcepts(List<ConceptDefinition> concepts) {
        if (concepts == null || concepts.isEmpty()) {
            return SourceRoutingDecision.empty();
        }
        Set<String> families = new LinkedHashSet<>();
        Set<String> entityTypes = new LinkedHashSet<>();
        Set<String> preferred = new LinkedHashSet<>();
        Map<String, String> reasons = new LinkedHashMap<>();
        for (ConceptDefinition concept : concepts) {
            families.addAll(concept.catalogFamilies());
            entityTypes.addAll(concept.entityTypes());
            for (String source : concept.preferredSources()) {
                String normalized = CatalogSourceRegistry.normalizeSearchSource(source);
                SourceCapability capability = capabilities.get(normalized);
                if (capability != null && supportsAny(capability, entityTypes, families)) {
                    preferred.add(normalized);
                    reasons.put(normalized, "preferred by concept registry and compatible with "
                            + concept.id() + "/" + String.join(",", concept.catalogFamilies()));
                }
            }
        }
        if (preferred.isEmpty()) {
            for (SourceCapability capability : capabilities.values()) {
                if (supportsAny(capability, entityTypes, families)) {
                    preferred.add(capability.source());
                    reasons.putIfAbsent(capability.source(), "source capability matches concept families "
                            + String.join(",", families));
                }
            }
        }
        return new SourceRoutingDecision(new ArrayList<>(families), new ArrayList<>(preferred), List.of(), reasons);
    }

    public Map<String, Object> plannerContext() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (SourceCapability capability : capabilities.values()) {
            out.put(capability.source(), Map.of(
                    "catalog_families", capability.catalogFamilies(),
                    "entity_types", capability.entityTypes()));
        }
        return out;
    }

    private static boolean supports(SourceCapability capability, String entityType, String catalogFamily) {
        boolean familyMatch = catalogFamily == null || catalogFamily.isBlank() || capability.catalogFamilies().contains(catalogFamily);
        boolean entityMatch = entityType == null || entityType.isBlank() || capability.entityTypes().contains(entityType);
        return familyMatch && entityMatch;
    }

    private static boolean supportsAny(SourceCapability capability, Set<String> entityTypes, Set<String> catalogFamilies) {
        boolean familyMatch = catalogFamilies == null
                || catalogFamilies.isEmpty()
                || catalogFamilies.stream().anyMatch(capability.catalogFamilies()::contains);
        boolean entityMatch = entityTypes == null
                || entityTypes.isEmpty()
                || entityTypes.stream().anyMatch(capability.entityTypes()::contains);
        return familyMatch && entityMatch;
    }

    private static Map<String, SourceCapability> load(ObjectMapper objectMapper) {
        Map<String, SourceCapability> out = new LinkedHashMap<>();
        try (InputStream in = SearchV2SourceCapabilityRegistry.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return out;
            }
            JsonNode sources = objectMapper.readTree(in).path("sources");
            sources.fields().forEachRemaining(entry -> {
                String source = CatalogSourceRegistry.normalizeSearchSource(entry.getKey());
                JsonNode node = entry.getValue();
                out.put(source, new SourceCapability(
                        source,
                        stringArray(node.path("catalog_families")),
                        stringArray(node.path("entity_types")),
                        node.path("notes").asText("")));
            });
        } catch (Exception ignored) {
            return Map.of();
        }
        return out;
    }

    private static List<String> stringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        node.forEach(item -> {
            String text = item.asText("").trim().toLowerCase(Locale.ROOT);
            if (!text.isBlank() && !out.contains(text)) {
                out.add(text);
            }
        });
        return out;
    }

    public record SourceCapability(
            String source,
            List<String> catalogFamilies,
            List<String> entityTypes,
            String notes) {}
}
