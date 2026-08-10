package cz.bankintel.search.v2.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private final List<EntityDefinition> entities;
    private final SearchV2SourceCapabilityRegistry sourceCapabilityRegistry;

    public ExactEntityResolver(ObjectMapper objectMapper, SearchV2SourceCapabilityRegistry sourceCapabilityRegistry) {
        this.entities = load(objectMapper);
        this.sourceCapabilityRegistry = sourceCapabilityRegistry;
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
            SourceRoutingDecision routing = sourceCapabilityRegistry.route(inferred);
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
