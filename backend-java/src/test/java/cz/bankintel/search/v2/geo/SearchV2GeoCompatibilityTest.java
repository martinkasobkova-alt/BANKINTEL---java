package cz.bankintel.search.v2.geo;

import static org.assertj.core.api.Assertions.assertThat;

import cz.bankintel.search.v2.schema.SearchCandidate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchV2GeoCompatibilityTest {

    @Test
    void stockExchangeSuffixActsAsFixedGeoEvidence() {
        assertThat(SearchV2GeoCompatibility.candidateMatchesRequestedGeo(
                        stock("KOMB.PR", "Komercni banka"), List.of("CZ"), null))
                .isTrue();
        assertThat(SearchV2GeoCompatibility.candidateMatchesRequestedGeo(
                        stock("KONN.F", "Komercni banka"), List.of("CZ"), null))
                .isFalse();
    }

    @Test
    void regionalAliasInCandidateTextCanSatisfyExplicitGeoForGlobalPriceRows() {
        SearchCandidate euGas = candidate(
                "PNGASEUUSDM",
                "Global price of Natural gas, EU",
                "fred",
                "",
                Map.of("catalog_family", "commodities"));
        SearchCandidate industryIndex = candidate(
                "NASDAQFUM",
                "Index prirodniho plynu",
                "fred",
                "",
                Map.of("catalog_family", "markets_indices"));

        assertThat(SearchV2GeoCompatibility.candidateMatchesRequestedGeo(euGas, List.of("EU"), null))
                .isTrue();
        assertThat(SearchV2GeoCompatibility.candidateMatchesRequestedGeo(industryIndex, List.of("EU"), null))
                .isFalse();
    }

    @Test
    void explicitSupportedGeographiesMustContainRequestedGeo() {
        SearchCandidate czechDataset = candidate(
                "dataset",
                "Dimensionable dataset",
                "eurostat",
                "dataset",
                Map.of("supported_geographies", List.of("CZ", "SK")));
        SearchCandidate austrianDataset = candidate(
                "dataset",
                "Dimensionable dataset",
                "eurostat",
                "dataset",
                Map.of("supported_geographies", List.of("AT")));

        assertThat(SearchV2GeoCompatibility.candidateMatchesRequestedGeo(czechDataset, List.of("CZ"), null))
                .isTrue();
        assertThat(SearchV2GeoCompatibility.candidateMatchesRequestedGeo(austrianDataset, List.of("CZ"), null))
                .isFalse();
    }

    @Test
    void geoCoverageSampleSatisfiesRequestedGeoForMultiCountryData360Series() {
        // Data360 geo-propagation fix: geo_coverage_sample is the row's actual per-country data
        // coverage (data360 currently always hardcodes territory to "GLOBAL" regardless of how many
        // countries a series really covers) - confirmed live before this fix: IMF_FSI_FSREIC (ROE of
        // insurance companies) carries "ESP" here but the candidate's own geo resolves to "" (a
        // multi-country series is never collapsed to one country - see
        // SearchV2CandidateNormalizerTest), so this coverage-set fallback is what makes it
        // geo-matchable at all.
        SearchCandidate insuranceRoeSpainAndGermany = candidate(
                "IMF_FSI|IMF_FSI_FSREIC",
                "ROE pojistoven",
                "data360",
                "IMF_FSI",
                Map.of("geo_coverage_sample", List.of("ESP", "DEU")));
        SearchCandidate insuranceRoeGermanyOnly = candidate(
                "IMF_FSI|IMF_FSI_FSRENI",
                "ROE pojistoven",
                "data360",
                "IMF_FSI",
                Map.of("geo_coverage_sample", List.of("DEU")));

        assertThat(SearchV2GeoCompatibility.candidateMatchesRequestedGeo(
                        insuranceRoeSpainAndGermany, List.of("ES"), null))
                .isTrue();
        assertThat(SearchV2GeoCompatibility.candidateMatchesRequestedGeo(
                        insuranceRoeGermanyOnly, List.of("ES"), null))
                .isFalse();
    }

    @Test
    void geoMembershipsAreDerivedFromCountryRegistrySets() {
        assertThat(SearchV2GeoCompatibility.membershipsFor(List.of("AT")))
                .containsExactly("EU", "euro_area", "OECD");
        assertThat(SearchV2GeoCompatibility.membershipsFor(List.of("PL")))
                .containsExactly("EU", "OECD");
        assertThat(SearchV2GeoCompatibility.membershipsFor(List.of("US")))
                .containsExactly("OECD");
        assertThat(SearchV2GeoCompatibility.membershipsFor(List.of("U2")))
                .containsExactly("euro_area");
    }

    @Test
    void fixedNationalCatalogProducesStructuredConflictForAnotherCountry() {
        SearchCandidate czechNationalSeries = candidate(
                "wages",
                "Average wages",
                "csu",
                "wages",
                Map.of());

        var assessment = SearchV2GeoCompatibility.assessCandidateGeo(
                czechNationalSeries, List.of("AT"), null);

        assertThat(assessment.status()).isEqualTo("source_scope_conflict");
        assertThat(assessment.hardConflict()).isTrue();
        assertThat(assessment.candidateInferred()).isEqualTo("CZ");
    }

    private static SearchCandidate stock(String id, String title) {
        return candidate(id, title, "stocks", id, Map.of("catalog_family", "markets_equities"));
    }

    private static SearchCandidate candidate(
            String id, String title, String source, String dataset, Map<String, Object> raw) {
        return new SearchCandidate(
                source + ":" + id.toLowerCase(),
                id,
                title,
                "",
                source,
                dataset,
                "",
                "D",
                "",
                "",
                List.of(),
                List.of(),
                List.of(),
                "",
                -1.0,
                "",
                List.of(),
                raw);
    }
}
