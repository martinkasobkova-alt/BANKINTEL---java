package cz.bankintel.search.v2.retrieval;

import cz.bankintel.search.CatalogCountryAliasRegistry;
import cz.bankintel.search.CatalogTextUtils;
import cz.bankintel.search.v2.ontology.SearchV2ConceptOntology;
import cz.bankintel.search.v2.ontology.SearchV2ConceptRegistry;
import cz.bankintel.search.v2.ontology.SearchV2InstitutionalSectorRegistry;
import cz.bankintel.search.v2.ontology.SearchV2MetricIntentRegistry;
import cz.bankintel.search.v2.schema.SearchQueryPlan;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SearchV2QueryExpander {

    private static final int MAX_VARIANTS = 8;
    private static final Set<String> LOW_INFORMATION_TERMS = Set.of(
            "a", "an", "and", "are", "at", "by", "data", "dataset", "from", "in", "jen", "katalog", "o", "only",
            "pouze", "rada", "rady", "search", "serie", "series", "source", "v", "ve", "z", "ze", "zdroj",
            "arad", "cnb", "csu", "czso", "eurostat", "ecb", "ecb2", "fred", "imf", "mmf", "bis", "oecd",
            "data360", "worldbank", "commodities", "komodity");

    private final SearchV2ConceptOntology conceptOntology;
    private final SearchV2ConceptRegistry conceptRegistry;
    private final SearchV2InstitutionalSectorRegistry institutionalSectorRegistry;
    private final SearchV2MetricIntentRegistry metricIntentRegistry;

    public List<String> expand(SearchQueryPlan plan) {
        if (plan.highConfidenceExactEntity()) {
            List<String> baseTerms = new ArrayList<>();
            for (String term : plan.firstPassSearchTerms()) {
                addExact(baseTerms, clean(term, plan.geographies()));
                if (baseTerms.size() >= MAX_VARIANTS) {
                    break;
                }
            }
            if (baseTerms.isEmpty()) {
                return List.of();
            }
            List<String> exact = new ArrayList<>();
            for (String geography : plan.geographies()) {
                for (String term : baseTerms.stream().limit(2).toList()) {
                    addExact(exact, term + " " + geography);
                }
                for (String alias : preferredGeoAliases(geography).stream()
                        .filter(value -> !value.equalsIgnoreCase(geography))
                        .limit(1)
                        .toList()) {
                    addExact(exact, baseTerms.getFirst() + " " + alias);
                }
            }
            for (String term : baseTerms) {
                addExact(exact, term);
            }
            return exact.stream().distinct().limit(MAX_VARIANTS).toList();
        }
        List<String> out = new ArrayList<>();
        List<String> geographies = plan.geographies() == null ? List.of() : plan.geographies();
        String original = clean(plan.originalQuery(), geographies);
        List<String> originalFxVariants = currencyPairVariants(plan.originalQuery());
        if (originalFxVariants.isEmpty()) {
            add(out, original);
        }
        for (String fxVariant : originalFxVariants) {
            add(out, fxVariant);
        }
        addStructuredSectorRecall(out, plan, geographies);
        addRegistryComposedVariants(out, plan, geographies);
        addInstitutionalSectorVariants(out, plan);
        List<String> expansionInputs = new ArrayList<>();
        if (plan.originalQuery() != null && !plan.originalQuery().isBlank()) {
            expansionInputs.add(plan.originalQuery());
        }
        if (!original.isBlank() && !expansionInputs.contains(original)) {
            expansionInputs.add(original);
        }
        for (String input : expansionInputs) {
            for (String expansion : conceptOntology.queryExpansionsFor(input)) {
                add(out, expansion);
                if (out.size() >= MAX_VARIANTS) {
                    break;
                }
            }
            if (out.size() >= MAX_VARIANTS) {
                break;
            }
        }
        addRepresentativePlannerTerms(out, plan, geographies);
        int protectedVariantCount = out.size();
        for (String term : plan.allSearchTerms()) {
            if (out.size() >= MAX_VARIANTS) {
                break;
            }
            if (conflictsWithPlanSignals(term, plan)) {
                continue;
            }
            String cleaned = clean(term, geographies);
            String[] tokens = cleaned.isBlank() ? new String[0] : cleaned.split("\\s+");
            if (tokens.length > 5) {
                for (String token : tokens) {
                    add(out, token);
                    if (out.size() >= MAX_VARIANTS) {
                        break;
                    }
                }
                if (out.size() >= MAX_VARIANTS) {
                    break;
                }
                continue;
            }
            if (hasPhrase(out) && cleaned.split("\\s+").length > 6) {
                continue;
            }
            if (hasPhrase(out) && cleaned.split("\\s+").length == 1) {
                continue;
            }
            add(out, cleaned);
            for (String fxVariant : currencyPairVariants(term)) {
                add(out, fxVariant);
            }
            if (out.size() >= MAX_VARIANTS) {
                break;
            }
        }
        ensureGeoVariants(
                out,
                preferredGeoBases(plan, original, geographies),
                geographies,
                protectedVariantCount);
        return out.stream().distinct().limit(MAX_VARIANTS).toList();
    }

    /**
     * LLM search-term stability fix (2026-07-31 audit): source routing was already made deterministic
     * (see {@code SearchV2QueryPlanner}'s metric-intent routing tier), but {@code
     * plan.exactSearchTerms()}/{@code primaryConcepts()} etc still carry the LLM planner's own raw
     * output whenever concept resolution isn't high-confidence - repeated identical queries like
     * "ziskovost penzijnich fondu" produced a different candidate pool and Top-1 each run, traced to
     * {@code addRegistryComposedVariants}/{@code addInstitutionalSectorVariants} finding their sector/
     * metric evidence in {@code plan.allSearchTerms()} (LLM-influenced) rather than the raw query.
     *
     * <p>This is the exact deterministic prefix {@link #expand} builds for its non-exact-entity
     * branch, replayed into a fresh list rather than {@code out} itself - a pure, side-effect-free
     * introspection of "what would always be produced for this input", independent of whatever the
     * LLM planner returned this call. Draws only from the raw query (never the LLM's rephrasing of
     * it), the resolved exact entity (deterministic regardless of the LLM), and {@code
     * metricIntentRegistry}/{@code institutionalSectorRegistry} matches against that same raw query.
     */
    public List<String> deterministicSearchTerms(SearchQueryPlan plan) {
        List<String> out = new ArrayList<>();
        List<String> geographies = plan.geographies() == null ? List.of() : plan.geographies();
        String original = clean(plan.originalQuery(), geographies);
        List<String> originalFxVariants = currencyPairVariants(plan.originalQuery());
        if (originalFxVariants.isEmpty()) {
            add(out, original);
        }
        for (String fxVariant : originalFxVariants) {
            add(out, fxVariant);
        }
        if (plan.entityResolution() != null) {
            String canonical = plan.entityResolution().canonicalName();
            if (canonical != null && !canonical.isBlank()) {
                add(out, canonical);
            }
        }
        addStructuredSectorRecall(out, plan, geographies);
        addRegistryComposedVariants(out, plan, geographies);
        addInstitutionalSectorVariants(out, plan);
        for (String input : Arrays.asList(plan.originalQuery(), original)) {
            if (input == null || input.isBlank()) {
                continue;
            }
            for (String expansion : conceptOntology.queryExpansionsFor(input)) {
                add(out, expansion);
                if (out.size() >= MAX_VARIANTS) {
                    return List.copyOf(out);
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * LLM term normalization step (rejection half): an LLM-sourced term conflicts with the plan's own
     * deterministic signals when it names a DIFFERENT, specifically registered metric or institutional
     * sector than the one the raw query itself resolved to - e.g. plan metric intent is "debt" but the
     * term is clearly about "cost". A term that resolves to nothing (unregistered/free-topic) never
     * conflicts - this mirrors {@code SearchV2MetricIntentRegistry}'s own "free_metric_intent is valid"
     * design, not a new gate. Only fires when the PLAN itself has a definite signal to conflict with,
     * so a query with no resolved metric/sector (most exploratory queries) is entirely unaffected.
     */
    private boolean conflictsWithPlanSignals(String term, SearchQueryPlan plan) {
        if (term == null || term.isBlank()) {
            return false;
        }
        List<String> planMetrics = plan.metricIntents();
        if (!planMetrics.isEmpty()) {
            String termMetric = metricIntentRegistry.resolve(term);
            if (!termMetric.isBlank() && !planMetrics.contains(termMetric)) {
                return true;
            }
        }
        List<String> planSectors = plan.institutionalSectors();
        if (!planSectors.isEmpty()) {
            String termSector = institutionalSectorRegistry.resolve(term);
            if (!termSector.isBlank() && !planSectors.contains(termSector)) {
                return true;
            }
        }
        List<String> planGeo = plan.geographies();
        if (planGeo != null && !planGeo.isEmpty()) {
            List<String> termGeo = cz.bankintel.search.CatalogGeoIntent.requestedGeoCodes(
                    cz.bankintel.search.CatalogGeoIntent.detectGeoIntent(term));
            if (!termGeo.isEmpty() && termGeo.stream().noneMatch(planGeo::contains)) {
                return true;
            }
        }
        return false;
    }

    private void addStructuredSectorRecall(
            List<String> out, SearchQueryPlan plan, List<String> geographies) {
        String sectorEvidence = plan.originalQuery();
        List<String> aliases = institutionalSectorRegistry.aliasesForResolvedSector(sectorEvidence);
        if (aliases.isEmpty() || !retrievalMetricTerms(sectorEvidence).isEmpty()) {
            return;
        }
        String geography = geographies == null || geographies.isEmpty() ? "" : geographies.getFirst();
        String preferredAlias = aliases.stream()
                .filter(alias -> alias.chars().allMatch(ch -> ch < 128))
                .findFirst()
                .orElse(aliases.getFirst());
        add(out, String.join(
                " ",
                java.util.stream.Stream.of(preferredAlias, geography)
                        .filter(value -> value != null && !value.isBlank())
                        .toList()));
    }

    private void addRepresentativePlannerTerms(
            List<String> out, SearchQueryPlan plan, List<String> geographies) {
        List<String> phrases = plan.allSearchTerms().stream()
                .filter(term -> !conflictsWithPlanSignals(term, plan))
                .map(term -> clean(term, geographies))
                .filter(term -> !term.isBlank() && term.contains(" "))
                .filter(term -> out.stream().noneMatch(existing -> sameSearchVariant(existing, term)))
                .distinct()
                .toList();
        if (phrases.isEmpty()) {
            return;
        }

        int left = 0;
        int right = phrases.size() - 1;
        boolean takeFromEnd = false;
        int added = 0;
        while (left <= right && added < 4 && out.size() < MAX_VARIANTS) {
            String candidate = takeFromEnd ? phrases.get(right--) : phrases.get(left++);
            int before = out.size();
            add(out, candidate);
            if (out.size() > before) {
                added++;
            }
            takeFromEnd = !takeFromEnd;
        }
    }

    /**
     * LLM search-term stability fix (2026-07-31 audit): sector evidence is detected from {@code
     * plan.originalQuery()} - the one input that never varies for a repeated identical query - not
     * from {@code plan.allSearchTerms()}, which mixes in the LLM planner's own paraphrase of the query
     * whenever concept resolution isn't high-confidence (see {@code SearchV2QueryPlanner.validatePlan}).
     * Same registry, same matching, just a stable evidence source.
     */
    private void addInstitutionalSectorVariants(List<String> out, SearchQueryPlan plan) {
        for (String equivalent : institutionalSectorRegistry.equivalentPhrases(plan.originalQuery())) {
            add(out, equivalent);
            return;
        }
    }

    /** See {@link #addInstitutionalSectorVariants}'s javadoc - same stable-evidence-source fix. */
    private void addRegistryComposedVariants(
            List<String> out, SearchQueryPlan plan, List<String> geographies) {
        List<String> sectorAliases = institutionalSectorRegistry.aliasesForResolvedSector(plan.originalQuery());
        List<String> metricTerms = retrievalMetricTerms(plan.originalQuery());
        if (sectorAliases.isEmpty() || metricTerms.isEmpty()) {
            return;
        }
        String geography = geographies == null || geographies.isEmpty() ? "" : geographies.getFirst();
        int added = 0;
        for (String sectorAlias : sectorAliases.stream().limit(2).toList()) {
            for (String metricTerm : metricTerms.stream().limit(2).toList()) {
                String composed = String.join(
                        " ",
                        java.util.stream.Stream.of(sectorAlias, metricTerm, geography)
                                .filter(value -> value != null && !value.isBlank())
                                .toList());
                int before = out.size();
                add(out, composed);
                if (out.size() > before) {
                    added++;
                }
                if (added >= 1 || out.size() >= MAX_VARIANTS) {
                    return;
                }
            }
        }
    }

    private List<String> retrievalMetricTerms(String query) {
        List<String> terms = new ArrayList<>(conceptRegistry.retrievalTermsForQuery(query));
        for (String expansion : conceptOntology.queryExpansionsFor(query)) {
            if (!terms.contains(expansion)) {
                terms.add(expansion);
            }
        }
        return terms;
    }

    private void add(List<String> out, String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.length() >= 2
                && meaningful(clean)
                && out.stream().noneMatch(existing -> sameSearchVariant(existing, clean))) {
            out.add(clean);
        }
    }

    private void addExact(List<String> out, String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.length() >= 2
                && clean.chars().anyMatch(Character::isLetterOrDigit)
                && !LOW_INFORMATION_TERMS.contains(clean.toLowerCase(Locale.ROOT))
                && out.stream().noneMatch(existing -> sameSearchVariant(existing, clean))) {
            out.add(clean);
        }
    }

    private String clean(String value, List<String> geographies) {
        if (value == null || value.isBlank()) {
            return "";
        }
        List<String> tokens = Arrays.stream(value.trim().split("\\s+"))
                .map(String::trim)
                .filter(token -> meaningfulToken(token, geographies))
                .toList();
        if (tokens.isEmpty()) {
            return "";
        }
        return String.join(" ", tokens);
    }

    private boolean meaningful(String value) {
        List<String> tokens = Arrays.stream(value.split("\\s+"))
                .filter(token -> meaningfulToken(token, List.of()))
                .toList();
        if (tokens.size() >= 2) {
            return true;
        }
        return tokens.size() == 1
                && (tokens.get(0).length() >= 4
                        || conceptOntology.isShortMeaningfulTerm(tokens.get(0).toLowerCase(Locale.ROOT)));
    }

    private static boolean meaningfulToken(String token, List<String> geographies) {
        String lower = token == null ? "" : token.trim().toLowerCase(Locale.ROOT);
        if (lower.length() < 2 || LOW_INFORMATION_TERMS.contains(lower) || requestedGeoToken(lower, geographies)) {
            return false;
        }
        return lower.chars().anyMatch(Character::isLetterOrDigit);
    }

    private static boolean requestedGeoToken(String token, List<String> geographies) {
        if (token == null || token.isBlank() || geographies == null || geographies.isEmpty()) {
            return false;
        }
        String folded = CatalogTextUtils.foldAscii(token);
        for (String geography : geographies) {
            for (String alias : CatalogCountryAliasRegistry.foldedAliasMatchTerms(geography)) {
                if (folded.equals(alias) || (alias.length() >= 4 && folded.startsWith(alias))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> withPreferredGeoAliases(String cleanedQuery, List<String> geographies) {
        if (cleanedQuery == null || cleanedQuery.isBlank() || geographies == null || geographies.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String geo : preferredGeoAliases(geographies.getFirst())) {
            out.add(cleanedQuery + " " + geo);
        }
        return out;
    }

    private static List<String> preferredGeoAliases(String geography) {
        List<String> out = new ArrayList<>();
        String canonicalCode = geography == null ? "" : geography.trim().toUpperCase(Locale.ROOT);
        if (!canonicalCode.isBlank()) {
            out.add(canonicalCode);
        }
        for (String alias : CatalogCountryAliasRegistry.foldedAliasMatchTerms(geography)) {
            if (alias.length() > 2 && !alias.equalsIgnoreCase(canonicalCode)) {
                out.add(alias);
                if (out.size() >= 3) {
                    break;
                }
            }
        }
        return out;
    }

    private static boolean hasPhrase(List<String> values) {
        return values.stream().anyMatch(v -> v != null && v.trim().contains(" "));
    }

    private List<String> preferredGeoBases(
            SearchQueryPlan plan, String original, List<String> geographies) {
        List<String> bases = new ArrayList<>();
        if (original != null && !original.isBlank()) {
            bases.add(original);
        }
        for (String term : plan.exactSearchTerms()) {
            String cleaned = clean(term, geographies);
            if (cleaned.isBlank() || sameSearchVariant(cleaned, original)) {
                continue;
            }
            if (cleaned.contains("_") || cleaned.split("\\s+").length < 2) {
                continue;
            }
            if (bases.stream().noneMatch(existing -> sameSearchVariant(existing, cleaned))) {
                bases.add(cleaned);
                break;
            }
        }
        for (SearchV2ConceptRegistry.ConceptDefinition definition : conceptRegistry.definitions(plan.primaryConcepts())) {
            if (!definition.retrievalTerms().isEmpty()) {
                String retrievalTerm = definition.retrievalTerms().getFirst();
                if (bases.stream().noneMatch(existing -> sameSearchVariant(existing, retrievalTerm))) {
                    bases.add(retrievalTerm);
                }
            }
            List<String> englishAliases = definition.aliasesByLanguage().getOrDefault("en", List.of());
            if (!englishAliases.isEmpty()) {
                String alias = englishAliases.getFirst();
                if (bases.stream().noneMatch(existing -> sameSearchVariant(existing, alias))) {
                    bases.add(alias);
                    break;
                }
            }
        }
        return bases;
    }

    private static void ensureGeoVariants(
            List<String> out, List<String> bases, List<String> geographies, int protectedPrefixSize) {
        if (bases == null || bases.isEmpty() || geographies == null || geographies.isEmpty()) {
            return;
        }
        List<String> variants = new ArrayList<>();
        String geography = geographies.getFirst();
        for (String base : bases) {
            variants.add(base + " " + geography);
        }
        for (String alias : preferredGeoAliases(geography).stream()
                .filter(value -> !value.equalsIgnoreCase(geography))
                .limit(2)
                .toList()) {
            variants.add(bases.getFirst() + " " + alias);
        }
        if (variants.isEmpty()) {
            return;
        }
        List<String> missing = variants.stream()
                .filter(variant -> out.stream().noneMatch(existing -> sameSearchVariant(existing, variant)))
                .limit(4)
                .toList();
        if (missing.isEmpty()) {
            return;
        }
        int freeSlots = Math.max(0, MAX_VARIANTS - out.size());
        int replaceableSlots = Math.max(0, out.size() - protectedPrefixSize);
        int availableSlots = Math.min(missing.size(), freeSlots + replaceableSlots);
        int replacementCount = Math.max(0, availableSlots - freeSlots);
        int replacementIndex = Math.max(protectedPrefixSize, out.size() - replacementCount);
        for (String variant : missing.stream().limit(availableSlots).toList()) {
            if (out.size() < MAX_VARIANTS) {
                out.add(variant);
            } else {
                out.set(replacementIndex++, variant);
            }
        }
    }

    private static boolean sameSearchVariant(String left, String right) {
        return CatalogTextUtils.normalizeTokenBoundaries(left).equals(CatalogTextUtils.normalizeTokenBoundaries(right));
    }

    private List<String> currencyPairVariants(String value) {
        String normalized = value == null ? "" : CatalogTextUtils.foldAscii(value).toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String rawToken : normalized.split("\\s+")) {
            String token = rawToken.replaceAll("[^a-z]", "");
            if (token.length() != 6) {
                continue;
            }
            String base = token.substring(0, 3);
            String quote = token.substring(3);
            if (!conceptOntology.isCurrencyCode(base) || !conceptOntology.isCurrencyCode(quote) || base.equals(quote)) {
                continue;
            }
            out.add(base + " " + quote);
            out.add("exchange rate " + base + " " + quote);
            out.add("fx " + base + " " + quote);
        }
        return out.stream().distinct().toList();
    }
}
