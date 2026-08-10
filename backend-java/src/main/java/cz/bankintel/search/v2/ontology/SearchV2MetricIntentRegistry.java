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
 * A synonym-normalization layer for financial metric intent (debt/indebtedness/loans/liabilities,
 * profitability/ROE, cost, assets...) - deliberately NOT a routing gate and NOT a closed ontology
 * retrieval depends on. Its only job is: given two pieces of text, recognize when they're talking
 * about the same metric despite using different words (e.g. a query saying "zadluzeni" and a
 * candidate titled "uvery" both belong to the "debt" cluster).
 *
 * <p>Unlike {@link SearchV2InstitutionalSectorRegistry} (used as an independent routing signal by
 * {@code SearchV2SectorRoutingGuard}), this registry is consumed only for RANKING - see {@code
 * SearchV2FinalReranker}. A metric absent from this registry is not an error state: {@code resolve}
 * returning blank means "free_metric_intent" - the caller falls back to plain lexical/vector overlap
 * with the query's own words, which works whether or not this registry has ever heard of the term.
 */
@Service
public class SearchV2MetricIntentRegistry {

    private static final String RESOURCE = "/search_v2/metric_intent_registry.json";

    private final List<MetricAlias> aliases;

    public SearchV2MetricIntentRegistry(ObjectMapper objectMapper) {
        this.aliases = load(objectMapper);
    }

    /** Resolved metric cluster id, or blank if the text names no metric this registry recognizes. */
    public String resolve(String text) {
        String normalized = normalize(text);
        if (normalized.isBlank()) {
            return "";
        }
        String padded = " " + normalized + " ";
        for (MetricAlias alias : aliases) {
            if (padded.contains(" " + alias.alias() + " ")) {
                return alias.metric();
            }
        }
        return "";
    }

    /**
     * The single longest alias that matched {@code text} (the same scan {@link #resolve} performs,
     * exposing the alias itself rather than only the cluster id it belongs to) - lets a caller
     * distinguish an EXACT term match ("roe" query, "roe" candidate) from a same-cluster but different
     * term ("roe" query, "zisk" candidate), which {@link #resolve} alone collapses to identical.
     */
    public String matchedAlias(String text) {
        String normalized = normalize(text);
        if (normalized.isBlank()) {
            return "";
        }
        String padded = " " + normalized + " ";
        for (MetricAlias alias : aliases) {
            if (padded.contains(" " + alias.alias() + " ")) {
                return alias.alias();
            }
        }
        return "";
    }

    /**
     * Every alias belonging to the metric cluster {@code text} resolves to - the expansion used to
     * recognize a synonym match in candidate text (e.g. resolving "debt" from a query, then checking
     * a candidate title for ANY of "debt"/"loans"/"uvery"/"zavazky"/...), not just the literal word the
     * query happened to use. Empty for unrecognized text - callers must fall back to plain overlap.
     */
    public List<String> aliasesForResolvedMetric(String text) {
        String metric = resolve(text);
        if (metric.isBlank()) {
            return List.of();
        }
        return aliases.stream()
                .filter(alias -> alias.metric().equals(metric))
                .map(MetricAlias::alias)
                .distinct()
                .toList();
    }

    private static List<MetricAlias> load(ObjectMapper objectMapper) {
        try (InputStream input = SearchV2MetricIntentRegistry.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing metric intent registry: " + RESOURCE);
            }
            JsonNode root = objectMapper.readTree(input);
            List<MetricAlias> out = new ArrayList<>();
            for (JsonNode metric : root.path("metrics")) {
                String id = metric.path("id").asText("").trim().toLowerCase(Locale.ROOT);
                if (id.isBlank()) {
                    continue;
                }
                for (JsonNode alias : metric.path("aliases")) {
                    String normalizedAlias = normalize(alias.asText(""));
                    if (!normalizedAlias.isBlank()) {
                        out.add(new MetricAlias(id, normalizedAlias));
                    }
                }
            }
            out.sort(Comparator.comparingInt((MetricAlias value) -> value.alias().length()).reversed());
            return List.copyOf(out);
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot load metric intent registry", ex);
        }
    }

    private static String normalize(String value) {
        return CatalogTextUtils.normalizeTokenBoundaries(value == null ? "" : value);
    }

    private record MetricAlias(String metric, String alias) {}
}
