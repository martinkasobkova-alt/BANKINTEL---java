package cz.bankintel.search.v2.evaluation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SearchV2GoldQuery(
        String id,
        String query,
        String intent,
        @JsonProperty("expected_concepts") List<String> expectedConcepts,
        @JsonProperty("acceptable_sources") List<String> acceptableSources,
        @JsonProperty("required_source") String requiredSource,
        @JsonProperty("expected_geo") List<String> expectedGeo,
        @JsonProperty("expected_geography") List<String> expectedGeography,
        @JsonProperty("forbidden_concepts") List<String> forbiddenConcepts,
        @JsonProperty("forbidden_sources") List<String> forbiddenSources,
        @JsonProperty("relevant_series_ids") List<String> relevantSeriesIds,
        @JsonProperty("relevant_concept_families") List<String> relevantConceptFamilies,
        @JsonProperty("forbidden_concept_families") List<String> forbiddenConceptFamilies,
        @JsonProperty("expected_clarification") Boolean expectedClarification,
        @JsonProperty("clarification_required") boolean clarificationRequired,
        @JsonProperty("expected_roles") List<String> expectedRoles,
        @JsonProperty("expected_primary_role") String expectedPrimaryRole,
        @JsonProperty("expected_supporting_role") String expectedSupportingRole,
        @JsonProperty("gold_series") List<String> goldSeries) {

    public List<String> expectedGeos() {
        return merge(expectedGeo, expectedGeography);
    }

    public List<String> relevantSeries() {
        return merge(relevantSeriesIds, goldSeries);
    }

    public List<String> expectedConceptSignals() {
        return merge(expectedConcepts, relevantConceptFamilies);
    }

    public List<String> forbiddenConceptSignals() {
        return merge(forbiddenConcepts, forbiddenConceptFamilies);
    }

    public boolean expectsClarification() {
        return clarificationRequired || Boolean.TRUE.equals(expectedClarification);
    }

    private static List<String> merge(List<String> left, List<String> right) {
        List<String> out = new ArrayList<>();
        append(out, left);
        append(out, right);
        return out.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
    }

    private static void append(List<String> out, List<String> values) {
        if (values != null) {
            out.addAll(values);
        }
    }
}
