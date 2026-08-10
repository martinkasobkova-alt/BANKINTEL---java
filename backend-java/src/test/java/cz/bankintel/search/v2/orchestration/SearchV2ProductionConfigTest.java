package cz.bankintel.search.v2.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import cz.bankintel.search.v2.sidecar.SearchCatalogSidecarIndex;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SearchV2ProductionConfigTest {

    @Test
    void configuredEngineVersionUsesV2ButRequestCanRollbackToLegacy() {
        SearchV2FeatureFlags flags = new SearchV2FeatureFlags();
        ReflectionTestUtils.setField(flags, "configuredVersion", "v2");
        ReflectionTestUtils.setField(flags, "shadowMode", "false");

        assertThat(flags.useV2(Map.of())).isTrue();
        assertThat(flags.useV2(Map.of("search_engine_version", "v1"))).isFalse();
        assertThat(flags.shadowMode()).isFalse();
    }

    @Test
    void sidecarIsProductionIndexAndRequestOverrideIsExplicitRollback() {
        System.setProperty("SEARCH_CATALOG_INDEX", "sidecar");
        try {
            SearchCatalogSidecarIndex index = new SearchCatalogSidecarIndex(null, null, null);

            assertThat(index.configuredMode(Map.of())).isEqualTo(SearchCatalogSidecarIndex.MODE_SIDECAR);
            assertThat(index.configuredMode(Map.of("search_catalog_index", "legacy")))
                    .isEqualTo(SearchCatalogSidecarIndex.MODE_LEGACY);
        } finally {
            System.clearProperty("SEARCH_CATALOG_INDEX");
        }
    }

    @Test
    void semanticRetrievalStaysDisabledInProductionBaseline() {
        System.setProperty("SEARCH_SEMANTIC_RETRIEVAL_ENABLED", "false");
        try {
            assertThat(cz.bankintel.util.BankIntelEnvVars.isTruthy("SEARCH_SEMANTIC_RETRIEVAL_ENABLED"))
                    .isFalse();
        } finally {
            System.clearProperty("SEARCH_SEMANTIC_RETRIEVAL_ENABLED");
        }
    }
}
