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

/**
 * A synonym-normalization layer for NACE Rev.2 SECTION-level industry (agriculture/construction/
 * manufacturing/...) - deliberately NOT a routing gate and NOT a closed ontology retrieval depends
 * on, same design as {@link SearchV2MetricIntentRegistry}. Its only job is: given a piece of text,
 * recognize which broad industry section (if any) it names, so search results naming a DIFFERENT
 * industry than the query can be told apart in ranking - see {@code SearchV2FinalReranker}.
 *
 * <p>Root problem this addresses: the catalog search index has exactly one row per dataset, not one
 * per dimension value (a dataset like {@code nama_10_a64_e} has 96 real NACE values but a single
 * index row) - so "zamestnanost ve stavebnictvi" and "...v zemedelstvi" hit the identical row with
 * identical relevance. This registry cannot fix retrieval (the index shape is unchanged), but lets
 * ranking distinguish them: the resolved section id ("F" for construction, "A" for agriculture...)
 * doubles as the real NACE/CPA code tried against a specific Eurostat candidate's own dimension
 * metadata (see {@code SearchV2FinalReranker#verifyIndustryHasData}) before a match is trusted.
 *
 * <p>Sections are deliberately coarse (~21 broad letters, not narrow subcategories) - a query naming
 * a specific manufacturing sub-activity (e.g. "vyroba nabytku") will not be distinguished from
 * another manufacturing sub-activity, only from a wholly different section. A sector absent from
 * this registry is not an error state: {@code resolve} returning blank means "free_industry_intent" -
 * the caller falls back to plain lexical/vector overlap, which works whether or not this registry
 * has ever heard of the term.
 */
@Service
public class SearchV2IndustrySectorRegistry {

    private static final String RESOURCE = "/search_v2/industry_sector_registry.json";

    private final List<SectorAlias> aliases;

    public SearchV2IndustrySectorRegistry(ObjectMapper objectMapper) {
        this.aliases = load(objectMapper);
    }

    /** Resolved NACE section id (e.g. "F" for construction), or blank if the text names no section this registry recognizes. */
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

    /**
     * The single longest alias that matched {@code text} (the same scan {@link #resolve} performs,
     * exposing the alias itself rather than only the section id it belongs to) - lets a caller
     * distinguish an EXACT term match from a same-section but different term, which {@link #resolve}
     * alone collapses to identical.
     */
    public String matchedAlias(String text) {
        String normalized = normalize(text);
        if (normalized.isBlank()) {
            return "";
        }
        String padded = " " + normalized + " ";
        for (SectorAlias alias : aliases) {
            if (padded.contains(" " + alias.alias() + " ")) {
                return alias.alias();
            }
        }
        return "";
    }

    private static List<SectorAlias> load(ObjectMapper objectMapper) {
        try (InputStream input = SearchV2IndustrySectorRegistry.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing industry sector registry: " + RESOURCE);
            }
            JsonNode root = objectMapper.readTree(input);
            List<SectorAlias> out = new ArrayList<>();
            for (JsonNode sector : root.path("sectors")) {
                String id = sector.path("id").asText("").trim().toUpperCase(Locale.ROOT);
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
            throw new IllegalStateException("Cannot load industry sector registry", ex);
        }
    }

    private static String normalize(String value) {
        return CatalogTextUtils.normalizeTokenBoundaries(value == null ? "" : value);
    }

    private record SectorAlias(String sector, String alias) {}
}
