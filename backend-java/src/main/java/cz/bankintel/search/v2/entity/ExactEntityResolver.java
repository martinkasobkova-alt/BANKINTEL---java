package cz.bankintel.search.v2.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.CatalogIndexStore;
import cz.bankintel.search.CatalogTextUtils;
import cz.bankintel.search.model.CatalogMapSupport;
import cz.bankintel.search.v2.schema.ExactEntityResolution;
import cz.bankintel.search.v2.schema.SearchQueryVariant;
import cz.bankintel.search.v2.schema.SourceRoutingDecision;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class ExactEntityResolver {

    private static final String RESOURCE = "/search_v2/exact_entity_registry.json";
    private static final Pattern SERIES_CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.:/^-]{2,40}$");
    private static final Set<String> QUERY_CONTEXT_WORDS = Set.of(
            "vyvoj", "vývoj", "hodnota", "hodnoty", "graf", "rada", "řada", "serie", "series", "index", "price",
            "cena", "kurz", "rate", "total", "return", "tr", "data", "casova", "časová", "time");
    /** Confidence assigned once a code-like query is verified to be a REAL, currently-indexed
     * dataset/series id (not just shape-matched) - see {@link #verifyAgainstCatalog}. A fixed
     * policy constant, not a hardcode of any specific dataset. */
    private static final double CATALOG_VERIFIED_CONFIDENCE = 0.95;

    private final List<EntityDefinition> entities;
    private final SearchV2SourceCapabilityRegistry sourceCapabilityRegistry;
    private final CatalogIndexStore catalogIndexStore;

    public ExactEntityResolver(
            ObjectMapper objectMapper,
            SearchV2SourceCapabilityRegistry sourceCapabilityRegistry,
            CatalogIndexStore catalogIndexStore) {
        this.entities = load(objectMapper);
        this.sourceCapabilityRegistry = sourceCapabilityRegistry;
        this.catalogIndexStore = catalogIndexStore;
    }

    public ResolutionResult resolve(String query) {
        String raw = query == null ? "" : query.trim();
        if (raw.isBlank()) {
            ExactEntityResolution open = ExactEntityResolution.openTopic("empty query");
            return new ResolutionResult(open, SourceRoutingDecision.empty(), List.of());
        }
        List<EntityMatch> matches = new ArrayList<>();
        for (EntityDefinition entity : entities) {
            double score = score(raw, entity);
            if (score >= 0.72) {
                matches.add(new EntityMatch(entity, score));
            }
        }
        matches.sort(Comparator.comparingDouble(EntityMatch::score).reversed());
        if (!matches.isEmpty()) {
            EntityMatch best = matches.getFirst();
            EntityDefinition entity = best.entity();
            Map<String, Object> attributes = attributesForQuery(entity, raw);
            ExactEntityResolution resolution = new ExactEntityResolution(
                    best.score() >= 0.88 ? "exact_entity" : "probable_entity",
                    best.score(),
                    entity.entityType(),
                    entity.canonicalName(),
                    entity.symbols(),
                    entity.aliases(),
                    entity.catalogFamily(),
                    entity.preferredSources(),
                    entity.exactTerms(),
                    entity.relatedEntities(),
                    best.score() < 0.88,
                    "Matched entity registry alias/symbol before broad query expansion.",
                    attributes);
            SourceRoutingDecision routing = sourceCapabilityRegistry.route(resolution);
            return new ResolutionResult(resolution, routing, variants(raw, resolution));
        }
        ExactEntityResolution inferred = inferCodeLikeEntity(raw);
        if (!"open_topic".equals(inferred.resolutionType())) {
            SourceRoutingDecision routing = routingFor(inferred);
            return new ResolutionResult(inferred, routing, variants(raw, inferred));
        }
        return new ResolutionResult(inferred, SourceRoutingDecision.empty(), List.of(new SearchQueryVariant(raw, "original_exact", 1.0)));
    }

    public Map<String, Object> plannerContext() {
        return Map.of(
                "entity_registry_version", "search-v2-exact-entities-2026-07-12",
                "source_capabilities", sourceCapabilityRegistry.plannerContext());
    }

    private ExactEntityResolution inferCodeLikeEntity(String query) {
        String compact = query.replaceAll("\\s+", "");
        if (looksLikeFxPair(compact)) {
            String canonical = compact.substring(0, 3).toUpperCase(Locale.ROOT) + "/"
                    + compact.substring(3).toUpperCase(Locale.ROOT);
            return new ExactEntityResolution(
                    "probable_entity",
                    0.82,
                    "fx_pair",
                    canonical,
                    List.of(canonical, compact.toUpperCase(Locale.ROOT)),
                    List.of(query),
                    "fx",
                    List.of("ecb2", "fred"),
                    List.of(query, canonical),
                    List.of(),
                    true,
                    "Generic six-letter currency-pair parser matched ISO-like FX pair.",
                    Map.of("instrument_type", "exchange_rate", "market", "FX"));
        }
        if (SERIES_CODE.matcher(compact).matches() && containsLetterAndDigit(compact)) {
            // Multi-word queries also compact down to this same code-shape ("unemployment rate
            // 2024" -> "unemploymentrate2024") - only a query typed as ONE token is worth a
            // catalog-existence check; verifying/narrowing a real sentence on a coincidental
            // shape match would be a regression, not a fix (see live investigation notes).
            CatalogMatch verified = query.contains(" ") ? null : verifyAgainstCatalog(compact);
            if (verified != null) {
                String canonical = verified.setId().toUpperCase(Locale.ROOT);
                return new ExactEntityResolution(
                        "exact_entity",
                        CATALOG_VERIFIED_CONFIDENCE,
                        "series_code",
                        canonical,
                        List.of(canonical, verified.setId()),
                        List.of(query),
                        "",
                        List.of(verified.source()),
                        List.of(query, canonical, verified.setId()),
                        List.of(),
                        false,
                        "Verified against the real catalog: an existing " + verified.source()
                                + " dataset/series id matches this query exactly.",
                        Map.of());
            }
            return new ExactEntityResolution(
                    "probable_entity",
                    0.78,
                    "series_code",
                    compact.toUpperCase(Locale.ROOT),
                    List.of(compact.toUpperCase(Locale.ROOT)),
                    List.of(query),
                    "",
                    List.of(),
                    List.of(query, compact.toUpperCase(Locale.ROOT)),
                    List.of(),
                    true,
                    "Generic code-like token parser matched a possible dataset/series code.",
                    Map.of());
        }
        return ExactEntityResolution.openTopic("No exact entity, symbol, ticker, code or known abbreviation matched.");
    }

    /**
     * Live-verifies a code-shaped query against the real, currently-indexed catalog (not a
     * hardcoded list), looping every known source until the first hit. Uses {@link
     * CatalogIndexStore#lookupRowIndexedOnly} - NOT {@code lookupRow} - since this speculatively
     * probes many sources expecting most to miss; {@code lookupRow}'s JSONL-scan fallback on a
     * miss is fine for a single targeted lookup but live-measured to add multiple seconds here
     * (a full per-source file scan on every one of the ~11 sources that don't have the id).
     * set_id casing isn't normalized across sources (confirmed live: Eurostat stores
     * "naio_10_pyp1620" lowercase), so each candidate string is tried as-is, lowercased, and
     * uppercased.
     */
    private CatalogMatch verifyAgainstCatalog(String compact) {
        List<String> candidateIds = new ArrayList<>(new LinkedHashSet<>(List.of(
                compact, compact.toLowerCase(Locale.ROOT), compact.toUpperCase(Locale.ROOT))));
        for (String source : sourceCapabilityRegistry.knownSources()) {
            for (String candidateId : candidateIds) {
                Optional<Map<String, Object>> hit = catalogIndexStore.lookupRowIndexedOnly(source, candidateId);
                if (hit.isPresent()) {
                    String realSetId = CatalogMapSupport.firstNonBlank(
                            hit.get().get("set_id"), hit.get().get("id"));
                    return new CatalogMatch(source, realSetId.isBlank() ? candidateId : realSetId);
                }
            }
        }
        return null;
    }

    /**
     * {@link SearchV2SourceCapabilityRegistry#route} requires a non-blank {@code catalogFamily} -
     * always blank for the generic series-code branch - so a catalog-verified resolution's
     * {@code preferredSources} would otherwise silently never reach {@code SourceRoutingDecision}.
     * Build the routing decision directly from the verified source instead, for this case only.
     */
    private SourceRoutingDecision routingFor(ExactEntityResolution resolution) {
        if ("exact_entity".equals(resolution.resolutionType())
                && resolution.preferredSources() != null
                && !resolution.preferredSources().isEmpty()
                && resolution.catalogFamily().isBlank()) {
            return new SourceRoutingDecision(List.of(), resolution.preferredSources(), List.of(), Map.of());
        }
        return sourceCapabilityRegistry.route(resolution);
    }

    private record CatalogMatch(String source, String setId) {}

    private static boolean looksLikeFxPair(String compact) {
        if (compact == null) {
            return false;
        }
        String token = compact.replaceAll("[^A-Za-z]", "").toUpperCase(Locale.ROOT);
        return token.length() == 6 && currencyCodes().contains(token.substring(0, 3)) && currencyCodes().contains(token.substring(3));
    }

    private static Set<String> currencyCodes() {
        return Set.of("EUR", "USD", "CZK", "JPY", "GBP", "CHF", "PLN", "HUF", "CAD", "AUD", "NZD", "SEK", "NOK", "DKK");
    }

    private static boolean containsLetterAndDigit(String value) {
        return value.chars().anyMatch(Character::isLetter) && value.chars().anyMatch(Character::isDigit);
    }

    private static List<SearchQueryVariant> variants(String originalQuery, ExactEntityResolution resolution) {
        List<SearchQueryVariant> out = new ArrayList<>();
        addVariant(out, originalQuery, "original_exact", 1.0);
        addVariant(out, resolution.canonicalName(), "canonical_name", 0.98);
        for (String symbol : resolution.symbols()) {
            addVariant(out, symbol, "symbol", 0.96);
        }
        for (String term : resolution.exactTerms()) {
            addVariant(out, term, "exact_alias", 0.94);
        }
        for (String alias : resolution.aliases()) {
            addVariant(out, alias, "exact_alias", 0.90);
        }
        for (String related : resolution.relatedEntities()) {
            addVariant(out, related, "related_entity", 0.25);
        }
        return out.stream().limit(16).toList();
    }

    private static void addVariant(List<SearchQueryVariant> out, String text, String role, double weight) {
        String value = text == null ? "" : text.trim();
        if (value.isBlank()) {
            return;
        }
        String normalized = CatalogTextUtils.normalizeTokenBoundaries(value);
        boolean exists = out.stream().anyMatch(existing ->
                CatalogTextUtils.normalizeTokenBoundaries(existing.text()).equals(normalized));
        if (!exists) {
            out.add(new SearchQueryVariant(value, role, weight));
        }
    }

    private static double score(String query, EntityDefinition entity) {
        String normalizedQuery = normalizeEntityText(query, false);
        String strippedQuery = normalizeEntityText(query, true);
        double best = 0.0;
        for (String candidate : entity.matchTerms()) {
            String normalizedTerm = normalizeEntityText(candidate, false);
            if (normalizedTerm.isBlank()) {
                continue;
            }
            if (normalizedQuery.equals(normalizedTerm)) {
                best = Math.max(best, 1.0);
            }
            if (strippedQuery.equals(normalizedTerm)) {
                best = Math.max(best, 0.96);
            }
            if (containsEntityTokenSequence(normalizedQuery, normalizedTerm)) {
                best = Math.max(best, 0.90);
            }
            if (containsEntityTokenSequence(strippedQuery, normalizedTerm)) {
                best = Math.max(best, 0.88);
            }
        }
        return best;
    }

    private static boolean containsEntityTokenSequence(String query, String term) {
        if (query == null || term == null || query.isBlank() || term.isBlank()) {
            return false;
        }
        return (" " + query + " ").contains(" " + term + " ");
    }

    private static String normalizeEntityText(String value, boolean removeContextWords) {
        String folded = CatalogTextUtils.foldAscii(value == null ? "" : value).toLowerCase(Locale.ROOT);
        folded = folded.replace("&", " and ").replace("+", " plus ");
        folded = folded.replaceAll("[^a-z0-9]+", " ").trim();
        if (!removeContextWords || folded.isBlank()) {
            return folded.replaceAll("\\s+", " ");
        }
        List<String> tokens = new ArrayList<>();
        for (String token : folded.split("\\s+")) {
            if (!QUERY_CONTEXT_WORDS.contains(token)) {
                tokens.add(token);
            }
        }
        return String.join(" ", tokens).replaceAll("\\s+", " ").trim();
    }

    private static List<EntityDefinition> load(ObjectMapper objectMapper) {
        List<EntityDefinition> out = new ArrayList<>();
        try (InputStream in = ExactEntityResolver.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return out;
            }
            JsonNode root = objectMapper.readTree(in);
            JsonNode entities = root.path("entities");
            if (!entities.isArray()) {
                return out;
            }
            for (JsonNode node : entities) {
                Map<String, Object> attributes = new LinkedHashMap<>();
                for (String key : List.of(
                        "instrument_type",
                        "market",
                        "index_family",
                        "measure_type",
                        "return_type",
                        "requested_return_type",
                        "geo_mode",
                        "fixed_geo",
                        "institution")) {
                    String value = node.path(key).asText("");
                    if (!value.isBlank()) {
                        attributes.put(key, value);
                    }
                }
                out.add(new EntityDefinition(
                        node.path("entity_type").asText(""),
                        node.path("canonical_name").asText(""),
                        node.path("catalog_family").asText(""),
                        stringArray(node.path("preferred_sources")),
                        stringArray(node.path("symbols")),
                        stringArray(node.path("aliases")),
                        stringArray(node.path("exact_terms")),
                        stringArray(node.path("related_entities")),
                        attributes));
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return out;
    }

    private static List<String> stringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        node.forEach(item -> {
            String text = item.isTextual() ? item.asText("") : CatalogMapSupport.str(item);
            if (!text.isBlank()) {
                out.add(text);
            }
        });
        return new ArrayList<>(out);
    }

    public record ResolutionResult(
            ExactEntityResolution entityResolution,
            SourceRoutingDecision sourceRouting,
            List<SearchQueryVariant> queryVariants) {
        public Map<String, Object> toMap() {
            return Map.of(
                    "entity_resolution", entityResolution.toMap(),
                    "source_routing", sourceRouting.toMap(),
                    "query_variants", queryVariants.stream().map(SearchQueryVariant::toMap).toList());
        }
    }

    private record EntityMatch(EntityDefinition entity, double score) {}

    private static Map<String, Object> attributesForQuery(EntityDefinition entity, String query) {
        Map<String, Object> attributes = new LinkedHashMap<>(entity.attributes() == null ? Map.of() : entity.attributes());
        String requestedReturnType = requestedReturnType(query);
        if (!requestedReturnType.isBlank() && Set.of("market_index", "equity_index").contains(entity.entityType())) {
            attributes.put("requested_return_type", requestedReturnType);
        }
        return attributes;
    }

    private static String requestedReturnType(String query) {
        String folded = normalizeEntityText(query, false);
        if (containsEntityTokenSequence(folded, "net total return")) {
            return "net_total_return";
        }
        if (containsEntityTokenSequence(folded, "total return")
                || containsEntityTokenSequence(folded, "gross return")
                || containsEntityTokenSequence(folded, "tr")) {
            return "total_return";
        }
        return "";
    }

    private record EntityDefinition(
            String entityType,
            String canonicalName,
            String catalogFamily,
            List<String> preferredSources,
            List<String> symbols,
            List<String> aliases,
            List<String> exactTerms,
            List<String> relatedEntities,
            Map<String, Object> attributes) {
        List<String> matchTerms() {
            List<String> out = new ArrayList<>();
            out.add(canonicalName);
            out.addAll(symbols == null ? List.of() : symbols);
            out.addAll(aliases == null ? List.of() : aliases);
            out.addAll(exactTerms == null ? List.of() : exactTerms);
            return out;
        }
    }
}
