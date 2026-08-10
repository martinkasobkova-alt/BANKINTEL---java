package cz.bankintel.search.v2.ontology;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.CatalogCountryAliasRegistry;
import cz.bankintel.search.CatalogGeoIntent;
import cz.bankintel.search.CatalogTextUtils;
import cz.bankintel.search.model.CatalogMapSupport;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class SearchV2ConceptRegistry {

    private static final String RESOURCE = "/search_v2/concept_registry.json";
    private static final Set<String> GENERIC_SINGLE_TOKENS = Set.of(
            "rate", "mira", "index", "value", "amount", "data", "series", "rada", "serie");

    private final List<ConceptDefinition> concepts;
    private final Map<String, ConceptDefinition> byId;
    // Perf fix (selectRerankPool/candidateMatchesRequiredConcepts bottleneck): alias/retrieval-term
    // strings come from the registry's own static JSON resource - they never change after load() - so
    // normalizing them (CatalogTextUtils.foldAscii + regex) is precomputed exactly once here, instead
    // of on every resolve()/candidateMatchesRequiredConcepts() call for every candidate of every
    // request. Behavior is unchanged: same filtering (blank/unsafe-single-token aliases dropped), same
    // per-alias entries (not deduplicated by normalized form) so resolve()'s match list is identical.
    private final Map<String, List<NormalizedAlias>> normalizedAliasesById;
    private final Map<String, List<String>> normalizedPhrasesById;
    // Temporary diagnostic counters (perf investigation only, not operational metrics) - see
    // SearchV2ConceptRegistryCallCountDiagnosticTest for how these are read.
    private final java.util.concurrent.atomic.AtomicLong resolveCallCount = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong resolveRequirementCallCount = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong candidateMatchesRequiredConceptsCallCount = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong conceptListScanCount = new java.util.concurrent.atomic.AtomicLong();

    public SearchV2ConceptRegistry(ObjectMapper objectMapper) {
        this.concepts = load(objectMapper);
        Map<String, ConceptDefinition> tmp = new LinkedHashMap<>();
        for (ConceptDefinition concept : concepts) {
            tmp.put(concept.id(), concept);
        }
        this.byId = Map.copyOf(tmp);

        Map<String, List<NormalizedAlias>> aliasesTmp = new LinkedHashMap<>();
        Map<String, List<String>> phrasesTmp = new LinkedHashMap<>();
        for (ConceptDefinition concept : concepts) {
            List<NormalizedAlias> normalizedAliases = new ArrayList<>();
            for (String alias : concept.aliases()) {
                String normalizedAlias = normalize(alias);
                if (normalizedAlias.isBlank() || isUnsafeSingleAlias(normalizedAlias)) {
                    continue;
                }
                normalizedAliases.add(new NormalizedAlias(alias, normalizedAlias, tokenCount(normalizedAlias)));
            }
            aliasesTmp.put(concept.id(), List.copyOf(normalizedAliases));

            LinkedHashSet<String> normalizedPhrases = new LinkedHashSet<>();
            for (String alias : concept.aliases()) {
                String normalized = normalize(alias);
                if (!normalized.isBlank()) {
                    normalizedPhrases.add(normalized);
                }
            }
            for (String term : concept.retrievalTerms()) {
                String normalized = normalize(term);
                if (!normalized.isBlank()) {
                    normalizedPhrases.add(normalized);
                }
            }
            phrasesTmp.put(concept.id(), List.copyOf(normalizedPhrases));
        }
        this.normalizedAliasesById = Map.copyOf(aliasesTmp);
        this.normalizedPhrasesById = Map.copyOf(phrasesTmp);
    }

    public ConceptResolution resolve(String query) {
        resolveCallCount.incrementAndGet();
        String normalizedQuery = normalize(query);
        if (normalizedQuery.isBlank()) {
            return ConceptResolution.empty();
        }
        List<String> queryForms = new ArrayList<>();
        queryForms.add(normalizedQuery);
        String withoutGeography = withoutDetectedGeography(query, normalizedQuery);
        if (!withoutGeography.isBlank() && !withoutGeography.equals(normalizedQuery)) {
            queryForms.add(withoutGeography);
        }
        List<ConceptMatch> matches = new ArrayList<>();
        for (ConceptDefinition concept : concepts) {
            for (NormalizedAlias normalizedAlias : normalizedAliasesById.get(concept.id())) {
                if (queryForms.stream().anyMatch(form -> containsPhrase(form, normalizedAlias.normalized()))) {
                    double confidence = normalizedAlias.tokenCount() > 1 ? 0.96 : 0.88;
                    matches.add(new ConceptMatch(
                            concept, normalizedAlias.original(), normalizedAlias.tokenCount(), confidence));
                }
            }
        }
        if (matches.isEmpty()) {
            return ConceptResolution.empty();
        }
        matches.sort(Comparator.comparingInt(ConceptMatch::tokenCount)
                .thenComparingDouble(ConceptMatch::confidence)
                .reversed());
        int bestTokenCount = matches.getFirst().tokenCount();
        LinkedHashMap<String, ConceptMatch> selected = new LinkedHashMap<>();
        for (ConceptMatch match : matches) {
            if (match.tokenCount() < bestTokenCount && !selected.isEmpty()) {
                break;
            }
            selected.putIfAbsent(match.concept().id(), match);
        }
        return new ConceptResolution(new ArrayList<>(selected.values()));
    }

    private static String withoutDetectedGeography(String query, String normalizedQuery) {
        List<String> codes = CatalogGeoIntent.requestedGeoCodes(CatalogGeoIntent.detectGeoIntent(query));
        if (codes.isEmpty()) {
            return normalizedQuery;
        }
        String cleaned = " " + normalizedQuery + " ";
        List<String> aliases = new ArrayList<>();
        for (String code : codes) {
            aliases.add(code);
            aliases.addAll(CatalogCountryAliasRegistry.aliasesFor(code));
        }
        aliases.sort(Comparator.comparingInt(String::length).reversed());
        for (String rawAlias : aliases) {
            if (rawAlias == null || rawAlias.isBlank()) {
                continue;
            }
            boolean wildcard = rawAlias.endsWith("*");
            String alias = normalize(wildcard ? rawAlias.substring(0, rawAlias.length() - 1) : rawAlias);
            if (alias.isBlank()) {
                continue;
            }
            String pattern = wildcard
                    ? "(?<![a-z0-9])" + java.util.regex.Pattern.quote(alias) + "[a-z0-9]*(?![a-z0-9])"
                    : "(?<![a-z0-9])" + java.util.regex.Pattern.quote(alias) + "(?![a-z0-9])";
            cleaned = cleaned.replaceAll(pattern, " ");
        }
        return cleaned.replaceAll("\\s+", " ").trim();
    }

    public boolean conflictsWithRequiredConcepts(String text, List<String> requiredConceptIds) {
        ConceptResolution detected = resolve(text);
        if (!detected.highConfidence() || requiredConceptIds == null || requiredConceptIds.isEmpty()) {
            return false;
        }
        Set<String> required = new LinkedHashSet<>(requiredConceptIds);
        for (ConceptDefinition detectedConcept : detected.concepts()) {
            if (isCompatibleWithAnyRequired(detectedConcept, required)) {
                continue;
            }
            return true;
        }
        return false;
    }

    public boolean matchesRequiredOrCompatibleConcepts(String text, List<String> requiredConceptIds) {
        Set<String> knownRequired = knownRequiredConcepts(requiredConceptIds);
        if (knownRequired.isEmpty()) {
            return true;
        }
        ConceptResolution detected = resolve(text);
        if (!detected.highConfidence()) {
            return false;
        }
        for (ConceptDefinition detectedConcept : detected.concepts()) {
            if (isCompatibleWithAnyRequired(detectedConcept, knownRequired)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Matches catalog evidence against the required concept vocabulary. Candidate titles are often
     * shorter than user-facing aliases, so retrieval terms are intentionally considered here too.
     *
     * <p>Perf fix: {@code requiredConceptIds} is resolved fresh on every call - callers iterating many
     * candidates against the same requirement (e.g. {@code SearchV2Service.selectRerankPool}) should
     * call {@link #resolveRequirement} once and use {@link #candidateMatchesRequiredConcepts(String,
     * ResolvedConceptRequirement)} instead. This overload is kept for callers that only ever check one
     * candidate, and delegates to the resolved-requirement path so both stay behaviorally identical.
     */
    public boolean candidateMatchesRequiredConcepts(String text, List<String> requiredConceptIds) {
        return candidateMatchesRequiredConcepts(text, resolveRequirement(requiredConceptIds));
    }

    /**
     * Resolves {@code requiredConceptIds} into an immutable, request-local {@link
     * ResolvedConceptRequirement} exactly once - the set of registry concept IDs compatible with the
     * requirement (direct match or via {@code compatibleConcepts}) never changes for a given
     * {@code requiredConceptIds} + registry snapshot, so this replaces what used to be an
     * {@code isCompatibleWithAnyRequired} scan of the whole registry, repeated for every candidate.
     */
    public ResolvedConceptRequirement resolveRequirement(List<String> requiredConceptIds) {
        resolveRequirementCallCount.incrementAndGet();
        Set<String> knownRequired = knownRequiredConcepts(requiredConceptIds);
        if (knownRequired.isEmpty()) {
            return ResolvedConceptRequirement.EMPTY;
        }
        conceptListScanCount.incrementAndGet();
        LinkedHashSet<String> compatibleConceptIds = new LinkedHashSet<>();
        for (ConceptDefinition concept : concepts) {
            if (isCompatibleWithAnyRequired(concept, knownRequired)) {
                compatibleConceptIds.add(concept.id());
            }
        }
        return new ResolvedConceptRequirement(Set.copyOf(knownRequired), Set.copyOf(compatibleConceptIds));
    }

    /**
     * Same matching semantics as {@link #candidateMatchesRequiredConcepts(String, List)}, but against
     * an already-{@link #resolveRequirement resolved} requirement - no per-candidate registry scan, no
     * per-candidate re-normalization of alias/retrieval-term strings (precomputed at construction).
     */
    public boolean candidateMatchesRequiredConcepts(String text, ResolvedConceptRequirement requirement) {
        candidateMatchesRequiredConceptsCallCount.incrementAndGet();
        if (requirement == null || requirement.isEmpty()) {
            return false;
        }
        ConceptResolution detected = resolve(text);
        for (ConceptDefinition concept : detected.concepts()) {
            if (requirement.isCompatible(concept.id())) {
                return true;
            }
        }
        String normalizedText = normalize(text);
        if (normalizedText.isBlank()) {
            return false;
        }
        conceptListScanCount.incrementAndGet();
        for (ConceptDefinition concept : concepts) {
            if (!requirement.isCompatible(concept.id())) {
                continue;
            }
            for (String normalizedPhrase : normalizedPhrasesById.get(concept.id())) {
                if (containsPhrase(normalizedText, normalizedPhrase)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---- Temporary diagnostic accessors (perf investigation only, not operational metrics) --------

    public long resolveCallCountForTest() {
        return resolveCallCount.get();
    }

    public long resolveRequirementCallCountForTest() {
        return resolveRequirementCallCount.get();
    }

    public long candidateMatchesRequiredConceptsCallCountForTest() {
        return candidateMatchesRequiredConceptsCallCount.get();
    }

    public long conceptListScanCountForTest() {
        return conceptListScanCount.get();
    }

    public List<ConceptDefinition> definitions(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<ConceptDefinition> out = new ArrayList<>();
        for (String id : ids) {
            ConceptDefinition concept = byId.get(id);
            if (concept != null) {
                out.add(concept);
            }
        }
        return out;
    }

    public List<String> retrievalTermsForQuery(String query) {
        ConceptResolution resolution = resolve(query);
        if (!resolution.highConfidence()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (ConceptDefinition concept : resolution.concepts()) {
            out.addAll(concept.retrievalTerms());
            out.addAll(concept.aliasesByLanguage().getOrDefault("en", List.of()));
        }
        return List.copyOf(out);
    }

    private Set<String> knownRequiredConcepts(List<String> requiredConceptIds) {
        if (requiredConceptIds == null || requiredConceptIds.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String id : requiredConceptIds) {
            String normalized = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
            if (byId.containsKey(normalized)) {
                out.add(normalized);
            }
        }
        return out;
    }

    public Map<String, Object> plannerContext() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (ConceptDefinition concept : concepts) {
            out.put(concept.id(), Map.of(
                    "aliases", concept.aliases(),
                    "catalog_families", concept.catalogFamilies(),
                    "entity_types", concept.entityTypes(),
                    "preferred_sources", concept.preferredSources(),
                    "compatible_concepts", concept.compatibleConcepts()));
        }
        return out;
    }

    private boolean isCompatibleWithAnyRequired(ConceptDefinition detected, Set<String> required) {
        if (required.contains(detected.id())) {
            return true;
        }
        for (String requiredId : required) {
            ConceptDefinition requiredConcept = byId.get(requiredId);
            if (requiredConcept == null) {
                continue;
            }
            if (requiredConcept.compatibleConcepts().contains(detected.id())
                    || detected.compatibleConcepts().contains(requiredId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUnsafeSingleAlias(String normalizedAlias) {
        return tokenCount(normalizedAlias) == 1 && GENERIC_SINGLE_TOKENS.contains(normalizedAlias);
    }

    private static boolean containsPhrase(String normalizedQuery, String normalizedAlias) {
        return (" " + normalizedQuery + " ").contains(" " + normalizedAlias + " ");
    }

    private static int tokenCount(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return 0;
        }
        return normalized.split("\\s+").length;
    }

    private static String normalize(String value) {
        String folded = CatalogTextUtils.foldAscii(value == null ? "" : value).toLowerCase(Locale.ROOT);
        return folded.replaceAll("[^a-z0-9]+", " ").replaceAll("\\s+", " ").trim();
    }

    private static List<ConceptDefinition> load(ObjectMapper objectMapper) {
        List<ConceptDefinition> out = new ArrayList<>();
        try (InputStream in = SearchV2ConceptRegistry.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return List.of();
            }
            JsonNode concepts = objectMapper.readTree(in).path("concepts");
            if (!concepts.isArray()) {
                return List.of();
            }
            for (JsonNode node : concepts) {
                String id = node.path("id").asText("").trim();
                if (id.isBlank()) {
                    continue;
                }
                out.add(new ConceptDefinition(
                        id,
                        aliases(node.path("aliases")),
                        aliasesByLanguage(node.path("aliases")),
                        stringArray(node.path("retrieval_terms")),
                        stringArray(node.path("catalog_families")),
                        stringArray(node.path("entity_types")),
                        stringArray(node.path("preferred_sources")),
                        stringArray(node.path("compatible_concepts"))));
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return List.copyOf(out);
    }

    private static List<String> aliases(JsonNode node) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(entry -> out.addAll(stringArray(entry.getValue())));
        }
        return new ArrayList<>(out);
    }

    private static Map<String, List<String>> aliasesByLanguage(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> out.put(
                entry.getKey().trim().toLowerCase(Locale.ROOT),
                stringArray(entry.getValue())));
        return Map.copyOf(out);
    }

    private static List<String> stringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        node.forEach(item -> {
            String text = item.isTextual() ? item.asText("") : CatalogMapSupport.str(item);
            if (!text.isBlank()) {
                out.add(text.trim().toLowerCase(Locale.ROOT));
            }
        });
        return new ArrayList<>(out);
    }

    public record ConceptDefinition(
            String id,
            List<String> aliases,
            Map<String, List<String>> aliasesByLanguage,
            List<String> retrievalTerms,
            List<String> catalogFamilies,
            List<String> entityTypes,
            List<String> preferredSources,
            List<String> compatibleConcepts) {}

    public record ConceptMatch(ConceptDefinition concept, String matchedAlias, int tokenCount, double confidence) {}

    /** Precomputed once at construction time - see the {@code normalizedAliasesById} field javadoc. */
    private record NormalizedAlias(String original, String normalized, int tokenCount) {}

    /**
     * Immutable, request-local result of {@link #resolveRequirement} - the set of registry concept IDs
     * a candidate's detected/evidence concept must belong to in order to satisfy one call's
     * {@code requiredConceptIds}. Holds only plain immutable {@link Set}s of {@link String} (concept
     * IDs) - no reference to mutable registry state, safe to read from any thread, never mutated after
     * construction.
     */
    public static final class ResolvedConceptRequirement {
        private static final ResolvedConceptRequirement EMPTY = new ResolvedConceptRequirement(Set.of(), Set.of());

        private final Set<String> knownRequiredConceptIds;
        private final Set<String> compatibleConceptIds;

        private ResolvedConceptRequirement(Set<String> knownRequiredConceptIds, Set<String> compatibleConceptIds) {
            this.knownRequiredConceptIds = knownRequiredConceptIds;
            this.compatibleConceptIds = compatibleConceptIds;
        }

        public boolean isEmpty() {
            return knownRequiredConceptIds.isEmpty();
        }

        public boolean isCompatible(String conceptId) {
            return compatibleConceptIds.contains(conceptId);
        }
    }

    public record ConceptResolution(List<ConceptMatch> matches) {
        public static ConceptResolution empty() {
            return new ConceptResolution(List.of());
        }

        public boolean highConfidence() {
            return !matches.isEmpty() && matches.getFirst().confidence() >= 0.88;
        }

        public List<String> conceptIds() {
            return matches.stream().map(match -> match.concept().id()).distinct().toList();
        }

        public List<ConceptDefinition> concepts() {
            return matches.stream().map(ConceptMatch::concept).distinct().toList();
        }

        public double confidence() {
            return matches.isEmpty() ? 0.0 : matches.getFirst().confidence();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("high_confidence", highConfidence());
            out.put("confidence", confidence());
            out.put("concept_ids", conceptIds());
            out.put("matches", matches.stream()
                    .map(match -> Map.of(
                            "concept_id", match.concept().id(),
                            "matched_alias", match.matchedAlias(),
                            "token_count", match.tokenCount(),
                            "confidence", match.confidence()))
                    .toList());
            return out;
        }
    }
}
