package cz.bankintel.search.v2.ontology;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.CatalogTextUtils;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class SearchV2InstitutionalSectorRegistry {

    private static final String RESOURCE = "/search_v2/institutional_sector_registry.json";

    private final List<SectorAlias> aliases;

    public SearchV2InstitutionalSectorRegistry(ObjectMapper objectMapper) {
        this.aliases = load(objectMapper);
    }

    public String resolve(String text) {
        String normalized = normalize(text);
        if (normalized.isBlank()) {
            return "";
        }
        String padded = " " + normalized + " ";
        for (SectorAlias alias : aliases) {
            if (padded.contains(" " + alias.alias() + " ")) {
                return alias.sector();
            }
        }
        return "";
    }

    public List<String> equivalentPhrases(String text) {
        String normalized = normalize(text);
        String sector = resolve(normalized);
        if (normalized.isBlank() || sector.isBlank()) {
            return List.of();
        }
        String matchedAlias = aliases.stream()
                .filter(alias -> alias.sector().equals(sector))
                .map(SectorAlias::alias)
                .filter(alias -> (" " + normalized + " ").contains(" " + alias + " "))
                .findFirst()
                .orElse("");
        if (matchedAlias.isBlank()) {
            return List.of();
        }
        return aliases.stream()
                .filter(alias -> alias.sector().equals(sector))
                .map(SectorAlias::alias)
                .filter(alias -> !alias.equals(matchedAlias))
                .map(alias -> normalized.replace(matchedAlias, alias))
                .distinct()
                .toList();
    }

    public List<String> aliasesForResolvedSector(String text) {
        String sector = resolve(text);
        if (sector.isBlank()) {
            return List.of();
        }
        return aliases.stream()
                .filter(alias -> alias.sector().equals(sector))
                .map(SectorAlias::alias)
                .distinct()
                .toList();
    }

    /**
     * The institutional sector a known concept inherently implies, derived from the concept's own
     * alias text (e.g. {@code bank_profitability}'s aliases all contain "bank" -&gt; {@code "banks"}) -
     * no separate per-concept sector table to maintain or drift out of sync. Blank for concepts whose
     * aliases don't name any institutional sector at all (e.g. {@code industrial_production},
     * {@code commodity_price}), which is correct: those concepts aren't sector-specific and can't
     * conflict with a query's explicit sector.
     */
    public String impliedSectorForConcepts(List<SearchV2ConceptRegistry.ConceptDefinition> concepts) {
        if (concepts == null) {
            return "";
        }
        for (SearchV2ConceptRegistry.ConceptDefinition concept : concepts) {
            String sector = resolve(String.join(" ", concept.aliases()));
            if (!sector.isBlank()) {
                return sector;
            }
        }
        return "";
    }

    private static List<SectorAlias> load(ObjectMapper objectMapper) {
        try (InputStream input = SearchV2InstitutionalSectorRegistry.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing institutional sector registry: " + RESOURCE);
            }
            JsonNode root = objectMapper.readTree(input);
            List<SectorAlias> out = new ArrayList<>();
            for (JsonNode sector : root.path("sectors")) {
                String id = sector.path("id").asText("").trim().toLowerCase(Locale.ROOT);
                if (id.isBlank()) {
                    continue;
                }
                for (JsonNode alias : sector.path("aliases")) {
                    String normalizedAlias = normalize(alias.asText(""));
                    if (!normalizedAlias.isBlank()) {
                        out.add(new SectorAlias(id, normalizedAlias));
                    }
                }
            }
            out.sort(Comparator.comparingInt((SectorAlias value) -> value.alias().length()).reversed());
            return List.copyOf(out);
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot load institutional sector registry", ex);
        }
    }

    private static String normalize(String value) {
        return CatalogTextUtils.normalizeTokenBoundaries(value == null ? "" : value);
    }

    private record SectorAlias(String sector, String alias) {}
}
