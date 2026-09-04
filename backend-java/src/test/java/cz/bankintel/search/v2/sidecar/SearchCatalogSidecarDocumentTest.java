package cz.bankintel.search.v2.sidecar;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Živě zjištěno: detail řady z AI/deep hledání (Search V2) neuměl ukázat klikací cestu v
 * katalogu (breadcrumb), přestože klasické hledání (`/api/catalog/suggest`) pro tutéž řadu
 * `full_path` bez problému vrací. Příčina: {@code toSearchRow()} dával původní syrový řádek
 * (kde `full_path` reálně je) jen do zanořeného klíče {@code raw}, ne na vrchní úroveň - a
 * {@code SearchV2CandidateNormalizer}/{@code SearchCandidate}'s RAW_PASSTHROUGH_KEYS čtou
 * `full_path`/`catalog_path` jen z vrchní úrovně.
 */
class SearchCatalogSidecarDocumentTest {

    private static SearchCatalogSidecarDocument doc(Map<String, Object> raw) {
        return new SearchCatalogSidecarDocument(
                "ICP/M.NL.N.102000.4.INX", // seriesId
                "ecb2", // source
                "", // dataset
                "HICP - Secondary education · Netherlands (Index)", // originalTitle
                "", // originalDescription
                "", // canonicalTitleCs
                "", // canonicalTitleEn
                "", // canonicalDescriptionCs
                "", // canonicalDescriptionEn
                "", // primaryConcept
                List.of(), // secondaryConcepts
                "", // measureType
                "", // economicObject
                "", // institutionalSector
                "", // counterpartSector
                "", // instrument
                "", // priceType
                "", // flowStock
                "", // industrySector
                "", // nominalReal
                "", // scope
                "NL", // geo
                "", // frequency
                "", // unit
                "", // seasonalAdjustment
                "", // priceBasis
                "", // datasetFamily
                "", // catalogFamily
                List.of(), // aliasesCs
                List.of(), // aliasesEn
                List.of(), // abbreviations
                List.of(), // negativeConcepts
                0.7, // metadataQualityScore
                "sidecar-v2-test", // enrichmentVersion
                "raw_catalog+taxonomy", // enrichmentSource
                "", // updatedAt
                "", // searchTextCs
                "", // searchTextEn
                raw);
    }

    @Test
    void toSearchRowSurfacesFullPathFromTheNestedRawRowToTheTopLevel() {
        Map<String, Object> raw = Map.of(
                "full_path", "ECB · ověřené řady > NL > ICP",
                "path", "ECB · ověřené řady > NL > ICP");

        Map<String, Object> row = doc(raw).toSearchRow(0.5, "HICP Education", List.of());

        assertThat(row.get("full_path")).isEqualTo("ECB · ověřené řady > NL > ICP");
        assertThat(row.get("catalog_path")).isEqualTo("ECB · ověřené řady > NL > ICP");
        // Původní zanořená raw musí zůstat netknutá - jiný kód se na ni spoléhá.
        assertThat(((Map<?, ?>) row.get("raw")).get("full_path")).isEqualTo("ECB · ověřené řady > NL > ICP");
    }

    @Test
    void toSearchRowFallsBackToPathWhenFullPathIsMissing() {
        Map<String, Object> raw = Map.of("path", "ECB · ověřené řady > NL > ICP");

        Map<String, Object> row = doc(raw).toSearchRow(0.5, "q", List.of());

        assertThat(row.get("full_path")).isEqualTo("ECB · ověřené řady > NL > ICP");
        assertThat(row.get("catalog_path")).isEqualTo("ECB · ověřené řady > NL > ICP");
    }

    @Test
    void toSearchRowLeavesFullPathBlankRatherThanThrowingWhenRawHasNeither() {
        Map<String, Object> row = doc(Map.of()).toSearchRow(0.5, "q", List.of());

        assertThat(row.get("full_path")).isEqualTo("");
        assertThat(row.get("catalog_path")).isEqualTo("");
    }
}
