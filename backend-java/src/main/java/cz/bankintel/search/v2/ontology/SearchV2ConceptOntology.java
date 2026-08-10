package cz.bankintel.search.v2.ontology;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class SearchV2ConceptOntology {

    private static final String RESOURCE = "/search_v2/concept_ontology.json";

    private final OntologyData data;
    private final Set<String> fallbackStopTerms;
    private final Set<String> shortMeaningfulTerms;
    private final Set<String> currencyCodes;
    private final Map<String, List<String>> requiredSignalAliases;
    private final List<QueryExpansionRule> queryExpansionRules;

    public SearchV2ConceptOntology(ObjectMapper objectMapper) {
        this.data = load(objectMapper);
        this.fallbackStopTerms = lowerSet(data.fallbackStopTerms());
        this.shortMeaningfulTerms = lowerSet(data.shortMeaningfulTerms());
        this.currencyCodes = lowerSet(data.currencyCodes());
        this.requiredSignalAliases = lowerMap(data.requiredSignalAliases());
        this.queryExpansionRules = data.queryExpansionRules() == null ? List.of() : data.queryExpansionRules();
    }

    public String version() {
        return data.version();
    }

    public Set<String> fallbackStopTerms() {
        return fallbackStopTerms;
    }

    public boolean isShortMeaningfulTerm(String token) {
        return shortMeaningfulTerms.contains(lower(token));
    }

    public boolean isCurrencyCode(String token) {
        return currencyCodes.contains(lower(token));
    }

    public boolean isRequiredSignal(String token) {
        return requiredSignalAliases.containsKey(lower(token));
    }

    public List<String> aliasesForSignal(String token) {
        String key = lower(token);
        return requiredSignalAliases.getOrDefault(key, List.of(key));
    }

    public List<String> contextOnlyTerms() {
        return data.contextOnlyTerms() == null ? List.of() : data.contextOnlyTerms();
    }

    public Map<String, List<String>> requiredSignalAliases() {
        return requiredSignalAliases;
    }

    public List<String> queryExpansionsFor(String query) {
        String folded = fold(query);
        if (folded.isBlank()) {
            return List.of();
        }
        return queryExpansionRules.stream()
                .filter(rule -> rule.matches(folded))
                .flatMap(rule -> (rule.addTerms() == null ? List.<String>of() : rule.addTerms()).stream())
                .map(String::trim)
                .filter(term -> !term.isBlank())
                .distinct()
                .toList();
    }

    private static OntologyData load(ObjectMapper objectMapper) {
        try (InputStream in = SearchV2ConceptOntology.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return defaults();
            }
            return objectMapper.readValue(in, OntologyData.class);
        } catch (Exception ex) {
            return defaults();
        }
    }

    private static Set<String> lowerSet(List<String> values) {
        return (values == null ? List.<String>of() : values).stream()
                .map(SearchV2ConceptOntology::lower)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Map<String, List<String>> lowerMap(Map<String, List<String>> values) {
        if (values == null) {
            return Map.of();
        }
        return values.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                entry -> lower(entry.getKey()),
                entry -> (entry.getValue() == null ? List.<String>of() : entry.getValue()).stream()
                        .map(SearchV2ConceptOntology::lower)
                        .filter(s -> !s.isBlank())
                        .distinct()
                        .toList()));
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static OntologyData defaults() {
        return new OntologyData(
                "fallback",
                List.of(
                        "and", "data", "dataset", "from", "inflace", "jen", "only", "price", "prices", "rada",
                        "rady", "serie", "series", "source", "the", "v", "ve", "with", "z", "ze"),
                List.of("cpi", "fx", "gdp", "hdp", "hicp", "ppi", "roa", "roe"),
                List.of(
                        "aud", "cad", "chf", "cny", "czk", "dkk", "eur", "gbp", "huf", "jpy", "nok",
                        "nzd", "pln", "sek", "usd"),
                Map.of(
                        "cpi", List.of("cpi", "consumer price"),
                        "gdp", List.of("gdp", "gross domestic product"),
                        "hdp", List.of("hdp", "gdp", "gross domestic product"),
                        "hicp", List.of("hicp", "harmonised index of consumer prices", "harmonized index of consumer prices"),
                        "ppi", List.of("ppi", "producer price"),
                        "roa", List.of("roa", "return on assets"),
                        "roe", List.of("roe", "return on equity")),
                List.of(),
                List.of("contribution", "contributions", "differential", "differentials", "weight", "weights"));
    }

    private static String fold(String value) {
        return lower(value)
                .replace('á', 'a')
                .replace('č', 'c')
                .replace('ď', 'd')
                .replace('é', 'e')
                .replace('ě', 'e')
                .replace('í', 'i')
                .replace('ň', 'n')
                .replace('ó', 'o')
                .replace('ř', 'r')
                .replace('š', 's')
                .replace('ť', 't')
                .replace('ú', 'u')
                .replace('ů', 'u')
                .replace('ý', 'y')
                .replace('ž', 'z');
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OntologyData(
            String version,
            @JsonProperty("fallback_stop_terms") List<String> fallbackStopTerms,
            @JsonProperty("short_meaningful_terms") List<String> shortMeaningfulTerms,
            @JsonProperty("currency_codes") List<String> currencyCodes,
            @JsonProperty("required_signal_aliases") Map<String, List<String>> requiredSignalAliases,
            @JsonProperty("query_expansion_rules") List<QueryExpansionRule> queryExpansionRules,
            @JsonProperty("context_only_terms") List<String> contextOnlyTerms) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QueryExpansionRule(
            @JsonProperty("trigger_any") List<String> triggerAny,
            @JsonProperty("trigger_all") List<String> triggerAll,
            @JsonProperty("add_terms") List<String> addTerms) {
        boolean matches(String foldedQuery) {
            boolean anyMatches = triggerAny == null || triggerAny.isEmpty()
                    || triggerAny.stream().map(SearchV2ConceptOntology::fold).anyMatch(foldedQuery::contains);
            boolean allMatches = triggerAll == null || triggerAll.isEmpty()
                    || triggerAll.stream().map(SearchV2ConceptOntology::fold).allMatch(foldedQuery::contains);
            return anyMatches && allMatches;
        }
    }
}
