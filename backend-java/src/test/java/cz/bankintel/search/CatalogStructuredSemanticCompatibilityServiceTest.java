package cz.bankintel.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.model.CatalogKeys;
import cz.bankintel.search.v2.normalization.SearchResultCanonicalMetadataService;
import cz.bankintel.search.v2.ontology.SearchV2InstitutionalSectorRegistry;
import cz.bankintel.search.v2.ontology.SearchV2MetricIntentRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CatalogStructuredSemanticCompatibilityServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CatalogStructuredSemanticCompatibilityService service =
            new CatalogStructuredSemanticCompatibilityService(new SearchResultCanonicalMetadataService(
                    new SearchV2InstitutionalSectorRegistry(objectMapper),
                    new SearchV2MetricIntentRegistry(objectMapper)));

    @Test
    void explicitSectorMetricAndGeoMatchWithoutDependingOnQueryWords() {
        Map<String, Object> row = service.evaluate(
                Map.of(
                        CatalogKeys.SOURCE_TYPE, "ecb2",
                        CatalogKeys.TITLE, "Technical label",
                        "geo", "SK",
                        "institutional_sector", "banks",
                        "metric_intent", "profitability"),
                Map.of(
                        CatalogKeys.REQUIRED_GEO_CODES, List.of("SK"),
                        CatalogKeys.INSTITUTIONAL_SECTORS, List.of("banks"),
                        CatalogKeys.METRIC_INTENTS, List.of("profitability")));

        assertThat(row.get(CatalogKeys.STRUCTURED_SEMANTIC_STATUS)).isEqualTo("match");
    }

    @Test
    void missingMetadataIsUnknownButExplicitConflictIsMismatch() {
        Map<String, Object> unknown = service.evaluate(
                Map.of(CatalogKeys.SOURCE_TYPE, "fred", CatalogKeys.TITLE, "Opaque series"),
                Map.of(CatalogKeys.METRIC_INTENTS, List.of("debt")));
        Map<String, Object> mismatch = service.evaluate(
                Map.of(
                        CatalogKeys.SOURCE_TYPE, "eurostat",
                        CatalogKeys.TITLE, "Total assets",
                        "metric_intent", "assets"),
                Map.of(CatalogKeys.METRIC_INTENTS, List.of("debt")));

        assertThat(unknown.get(CatalogKeys.STRUCTURED_SEMANTIC_STATUS)).isEqualTo("unknown");
        assertThat(mismatch.get(CatalogKeys.STRUCTURED_SEMANTIC_STATUS)).isEqualTo("mismatch");
    }

    @Test
    void oneMatchingDimensionWithOtherRequiredMetadataMissingIsOnlyPartial() {
        Map<String, Object> row = service.evaluate(
                Map.of(
                        CatalogKeys.SOURCE_TYPE, "ecb2",
                        CatalogKeys.TITLE, "Opaque Slovak series",
                        "geo", "SK"),
                Map.of(
                        CatalogKeys.REQUIRED_GEO_CODES, List.of("SK"),
                        CatalogKeys.INSTITUTIONAL_SECTORS, List.of("banks"),
                        CatalogKeys.METRIC_INTENTS, List.of("profitability")));

        assertThat(row.get(CatalogKeys.STRUCTURED_SEMANTIC_STATUS)).isEqualTo("partial");
    }
}
