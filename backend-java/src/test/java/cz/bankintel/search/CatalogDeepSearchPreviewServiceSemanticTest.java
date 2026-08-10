package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cz.bankintel.search.model.CatalogKeys;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CatalogDeepSearchPreviewServiceSemanticTest {

    @Test
    void previewActionableRequiresWholeQueryShape() {
        String query = "zisk bank cesko";
        Map<String, Object> geo = CatalogGeoIntent.detectGeoIntent(query);
        CatalogQueryRelevanceProfile profile = CatalogQueryRelevanceProfile.from(query, geo);

        assertTrue(CatalogDeepSearchPreviewService.isPreviewSemanticallyActionable(
                row("arad", "1013:SBBBM06911", "Bilance obchodních bank - Zisk běžného období"),
                query,
                geo,
                profile));
        assertFalse(CatalogDeepSearchPreviewService.isPreviewSemanticallyActionable(
                row("data360", "IMF_BOP|BFPEISRV", "BOP, reinvestice zisků"),
                query,
                geo,
                profile));
    }

    @Test
    void aiRelevantPreviewCanSatisfyActionableTarget() {
        String query = "roa bank";
        Map<String, Object> geo = CatalogGeoIntent.detectGeoIntent(query);
        CatalogQueryRelevanceProfile profile = CatalogQueryRelevanceProfile.from(query, geo);
        Map<String, Object> row = row("ecb2", "CBD2/A.U2.ROA", "ROA eurozóny");
        row.put("_ai_relevant", true);

        assertTrue(CatalogDeepSearchPreviewService.isPreviewSemanticallyActionable(row, query, geo, profile));
    }

    @Test
    void structuredMatchOverridesBrittleLexicalGateButConflictDoesNot() {
        String query = "opaque user wording";
        Map<String, Object> geo = CatalogGeoIntent.detectGeoIntent(query);
        CatalogQueryRelevanceProfile profile = CatalogQueryRelevanceProfile.from(query, geo);
        Map<String, Object> matching = row("ecb2", "SERIES-1", "Technical label");
        matching.put(CatalogKeys.STRUCTURED_SEMANTIC_STATUS, "match");
        Map<String, Object> conflicting = row("ecb2", "SERIES-2", "Exact opaque user wording");
        conflicting.put(CatalogKeys.STRUCTURED_SEMANTIC_STATUS, "mismatch");

        assertTrue(CatalogDeepSearchPreviewService.isPreviewSemanticallyActionable(
                matching, query, geo, profile));
        assertFalse(CatalogDeepSearchPreviewService.isPreviewSemanticallyActionable(
                conflicting, query, geo, profile));
    }

    private static Map<String, Object> row(String source, String setId, String title) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(CatalogKeys.SOURCE_TYPE, source);
        row.put(CatalogKeys.CATALOG_ID, source);
        row.put(CatalogKeys.SET_ID, setId);
        row.put("title", title);
        row.put(CatalogKeys.PREVIEW_STATUS, "verified");
        row.put(CatalogKeys.PREVIEW_AVAILABLE, true);
        row.put("preview_row_count", 10);
        return row;
    }
}
