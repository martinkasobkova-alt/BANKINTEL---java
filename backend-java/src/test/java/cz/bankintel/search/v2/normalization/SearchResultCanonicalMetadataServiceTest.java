package cz.bankintel.search.v2.normalization;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.v2.ontology.SearchV2InstitutionalSectorRegistry;
import cz.bankintel.search.v2.ontology.SearchV2MetricIntentRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchResultCanonicalMetadataServiceTest {

    private final SearchResultCanonicalMetadataService service = new SearchResultCanonicalMetadataService(
            new SearchV2InstitutionalSectorRegistry(new ObjectMapper()),
            new SearchV2MetricIntentRegistry(new ObjectMapper()));

    @Test
    void canonicalizesSourceAliasAndDirectGeo() {
        Map<String, Object> row = service.enrich(Map.of("source", "ECB", "geo", "AUT"));

        assertThat(row.get("canonical_source_id")).isEqualTo("ecb2");
        assertThat(row.get("canonical_geo_codes")).isEqualTo(List.of("AT"));
        assertThat(row.get("geo_scope")).isEqualTo("single_country");
        assertThat(provenance(row, "geo")).contains("fixed_field:geo");
    }

    @Test
    void derivesGeoOnlyFromDimensions() {
        Map<String, Object> row = service.enrich(Map.of(
                "source", "eurostat",
                "dimensions", Map.of("geo", Map.of("values", List.of("DEU")))));

        assertThat(row.get("canonical_geo_codes")).isEqualTo(List.of("DE"));
        assertThat(provenance(row, "geo")).contains("dimensions:dimensions.geo");
    }

    @Test
    void preservesMultiCountryCoverageWithoutInventingOneCountry() {
        Map<String, Object> row = service.enrich(Map.of(
                "source", "data360",
                "raw", Map.of("geo_coverage_sample", List.of("AUT", "DEU", "FRA"))));

        assertThat(row.get("canonical_geo_codes")).isEqualTo(List.of("AT", "DE", "FR"));
        assertThat(row.get("geo_scope")).isEqualTo("multi_country");
        assertThat(provenance(row, "geo")).contains("coverage_set:raw.geo_coverage_sample");
    }

    @Test
    void derivesExplicitSectorAndMetricFromCanonicalCatalogFields() {
        Map<String, Object> row = service.enrich(Map.of(
                "source", "ecb2",
                "raw", Map.of(
                        "institutional_sector", "banks",
                        "primary_concept", "bank profitability")));

        assertThat(row.get("canonical_sector_ids")).isEqualTo(List.of("banks"));
        assertThat(row.get("canonical_metric_intents")).isEqualTo(List.of("profitability"));
        assertThat(provenance(row, "sector")).contains("fixed_field:raw.institutional_sector");
    }

    @Test
    void derivesSectorFromDimensions() {
        Map<String, Object> row = service.enrich(Map.of(
                "source", "imf",
                "available_dimensions", Map.of("institutional_sector", List.of("insurance companies"))));

        assertThat(row.get("canonical_sector_ids")).isEqualTo(List.of("insurance"));
        assertThat(provenance(row, "sector"))
                .contains("dimensions:available_dimensions.institutional_sector");
    }

    @Test
    void contradictoryExplicitMetadataRemainsAmbiguous() {
        Map<String, Object> row = service.enrich(Map.of(
                "source", "eurostat",
                "geo", "AT",
                "country", "DE",
                "institutional_sector", "banks",
                "raw", Map.of("institutional_sector", "insurance companies")));

        assertThat(row.get("canonical_geo_codes")).isEqualTo(List.of());
        assertThat(row.get("geo_scope")).isEqualTo("ambiguous");
        assertThat(row.get("canonical_sector_ids")).isEqualTo(List.of());
        assertThat(row.get("sector_scope")).isEqualTo("ambiguous");
    }

    @Test
    void missingMetadataRemainsUnknown() {
        Map<String, Object> row = service.enrich(Map.of("source", "fred", "title", "Unclassified series"));

        assertThat(row.get("canonical_geo_codes")).isEqualTo(List.of());
        assertThat(row.get("canonical_sector_ids")).isEqualTo(List.of());
        assertThat(row.get("canonical_metric_intents")).isEqualTo(List.of());
        assertThat(row.get("geo_scope")).isEqualTo("unknown");
        assertThat(row.get("sector_scope")).isEqualTo("unknown");
    }

    @SuppressWarnings("unchecked")
    private static List<String> provenance(Map<String, Object> row, String key) {
        Map<String, List<String>> provenance =
                (Map<String, List<String>>) row.get("canonical_metadata_provenance");
        return provenance.get(key);
    }
}
