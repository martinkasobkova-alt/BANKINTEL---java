package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CatalogPreviewSetIdSupportTest {

    @BeforeEach
    void setUp() {
        System.setProperty("ARAD_API_KEY", "test-key");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("ARAD_API_KEY");
    }

    @Test
    void aradCompositeDatasetIndicatorIdIsPreviewFetchable() {
        assertTrue(CatalogPreviewSetIdSupport.isPreviewFetchable("arad", "1013:SBBBM06931", Map.of()));
        assertTrue(CatalogPreviewSetIdSupport.isPreviewFetchable("arad", "1013", Map.of()));
        assertFalse(CatalogPreviewSetIdSupport.isPreviewFetchable("arad", "ROE_BANKS_PILOT", Map.of()));
    }

    @Test
    void eurostatEnrichmentAliasResolvesToParentDataset() {
        Map<String, Object> row = Map.of(
                "set_id", "sts_inpr_m_manufacturing_total",
                "dataset", "sts_inpr_m",
                "dataset_id", "sts_inpr_m");
        assertEquals(
                "sts_inpr_m",
                CatalogPreviewSetIdSupport.resolvePreviewSetId("eurostat", "sts_inpr_m_manufacturing_total", row));
        assertTrue(CatalogPreviewSetIdSupport.isPreviewFetchable(
                "eurostat", "sts_inpr_m_manufacturing_total", row));
    }

    @Test
    void eurostatEnrichmentAliasResolvesFromFrequencyTailWithoutDatasetField() {
        assertEquals(
                "sts_intv_m",
                CatalogPreviewSetIdSupport.resolvePreviewSetId(
                        "eurostat", "sts_intv_m_manufacturing_turnover", Map.of()));
        assertEquals(
                "sts_intvd_m",
                CatalogPreviewSetIdSupport.resolvePreviewSetId(
                        "eurostat", "sts_intvd_m_manufacturing_turnover_domestic", Map.of()));
    }

    @Test
    void eurostatNaceCodeSuffixIsNotStrippedAsAnEnrichmentLabel() {
        // sts_inpr_m_c29 is its own distinct, independently-fetchable Eurostat dataset (industrial
        // production for NACE C29 specifically) - not a label-decorated alias of sts_inpr_m. Stripping
        // the "_c29" the same way "_manufacturing_total" gets stripped would resolve the live preview
        // fetch to the WRONG (parent, not NACE-specific) dataset and return empty data for it.
        assertEquals(
                "sts_inpr_m_c29",
                CatalogPreviewSetIdSupport.resolvePreviewSetId("eurostat", "sts_inpr_m_c29", Map.of()));
        assertEquals(
                "sts_inppd_m_c29",
                CatalogPreviewSetIdSupport.resolvePreviewSetId("eurostat", "sts_inppd_m_c29", Map.of()));
        assertEquals(
                "sts_intvd_m_c29",
                CatalogPreviewSetIdSupport.resolvePreviewSetId("eurostat", "sts_intvd_m_c29", Map.of()));
        assertEquals(
                "sts_inpr_m_c291",
                CatalogPreviewSetIdSupport.resolvePreviewSetId("eurostat", "sts_inpr_m_c291", Map.of()));
        // Real English enrichment labels must still be stripped.
        assertEquals(
                "sts_intv_m",
                CatalogPreviewSetIdSupport.resolvePreviewSetId(
                        "eurostat", "sts_intv_m_manufacturing_turnover", Map.of()));
    }

    @Test
    void eurostatRealDatasetCodePassesThrough() {
        assertEquals(
                "sts_inpr_m",
                CatalogPreviewSetIdSupport.resolvePreviewSetId("eurostat", "sts_inpr_m", Map.of()));
        assertTrue(CatalogPreviewSetIdSupport.isPreviewFetchable("eurostat", "sts_inpr_m", Map.of()));
    }
}
