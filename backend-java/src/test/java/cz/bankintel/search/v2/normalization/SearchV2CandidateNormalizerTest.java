package cz.bankintel.search.v2.normalization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchV2CandidateNormalizerTest {

    private final SearchV2CandidateNormalizer normalizer = new SearchV2CandidateNormalizer();

    /**
     * Data360 geo-propagation fix: {@code geo_coverage_sample} is the row's actual per-country data
     * coverage (computed at mirror time from the countries genuinely fetched for that indicator) -
     * unlike {@code territory}, which data360 currently always hardcodes to "GLOBAL" regardless of
     * how many countries the series really covers. Confirmed live before this fix (search-relevance
     * audit, 2026-07-31): IMF_FSI_FSREIC (insurance ROE) carries "ESP" in geo_coverage_sample but
     * resolved to geo="" - so a Spain-scoped query could never geo-match it.
     */
    @Test
    void singleCountryGeoCoverageSampleIsUsedDirectlyAsTheCandidateGeo() {
        var candidate = normalizer.normalize(
                "data360",
                Map.of(
                        "set_id", "IMF_FSI|IMF_FSI_FSREIC_SINGLE",
                        "name", "ROE pojistoven",
                        "territory", "GLOBAL",
                        "geo_coverage_sample", List.of("ESP")),
                "roe pojistoven spanelsko");

        assertThat(candidate.geo()).isEqualTo("ES");
    }

    @Test
    void multiCountryGeoCoverageSampleDoesNotCollapseToASingleCountry() {
        // Must NOT resolve to any single country here - a genuinely multi-country series would be
        // misrepresented. The multi-country case is handled as a coverage-set match in
        // SearchV2GeoCompatibility instead (see SearchV2GeoCompatibilityTest).
        var candidate = normalizer.normalize(
                "data360",
                Map.of(
                        "set_id", "IMF_FSI|IMF_FSI_FSREIC",
                        "name", "ROE pojistoven",
                        "territory", "GLOBAL",
                        "geo_coverage_sample", List.of("ESP", "DEU", "FRA")),
                "roe pojistoven");

        assertThat(candidate.geo()).isBlank();
        assertThat(candidate.raw().get("geo_coverage_sample")).isEqualTo(List.of("ESP", "DEU", "FRA"));
    }

    @Test
    void fixedNationalSourceScopeWinsOverGenericGeoDimensionLabel() {
        var candidate = normalizer.normalize(
                "csu",
                Map.of(
                        "set_id", "WAGES",
                        "title", "Average wages",
                        "geo", "Stát"),
                "average wages");

        assertThat(candidate.geo()).isEqualTo("CZ");
    }

    @Test
    void extractsIso2GeoFromImfIso3SeriesId() {
        var candidate = normalizer.normalize(
                "imf",
                Map.of(
                        "set_id", "IMF|IMF.RES|WEO|9.0.0|POL.NGDP_D",
                        "name", "Polsko · HDP deflátor"),
                "HDP polsko");

        assertThat(candidate.geo()).isEqualTo("PL");
    }
}
