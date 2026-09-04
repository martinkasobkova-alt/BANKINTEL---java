package cz.bankintel.explore;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Živě zjištěno: appka `request.uploadIds()` do detailní Explorer analýzy vůbec nezapojovala.
 * Po zapojení musí respektovat přepínač „Strict private"/„Anonymní souhrny" přesně v místě, kde
 * se text skládá do promptu pro OpenAI - {@link ExploreSectionBucketService#sectionContextBullets}.
 */
class ExploreSectionBucketServiceTest {

    private static Map<String, Object> catalogRow(String title, String line) {
        return Map.of("source_type", "eurostat", "title", title, "data_context_line", line);
    }

    private static Map<String, Object> uploadRow(String title, String fullLine, String safeSummaryLine) {
        return Map.of(
                "source_type", "user_upload",
                "title", title,
                "data_context_line", fullLine,
                "data_context_line_safe_summary", safeSummaryLine);
    }

    @Test
    void catalogRowsAreNeverMaskedRegardlessOfPrivacyMode() {
        List<Map<String, Object>> items = List.of(catalogRow("HDP", "HDP [eurostat/nama_10_gdp]: poslední hodnota 105.2 (2025-Q2)"));

        String bullets = ExploreSectionBucketService.sectionContextBullets(
                items, 9000, ExploreUserDataPrivacy.STRICT_PRIVATE);

        assertThat(bullets).contains("poslední hodnota 105.2");
    }

    @Test
    void strictPrivateOmitsUploadedSeriesFromAiPromptEntirely() {
        List<Map<String, Object>> items = List.of(
                catalogRow("HDP", "HDP [eurostat/nama_10_gdp]: poslední hodnota 105.2 (2025-Q2)"),
                uploadRow(
                        "trzby.csv",
                        "trzby.csv [user_upload/upload-1]: poslední hodnota 21.8 (2025-06), trend rostoucí, YoY 3.8 %",
                        "trzby.csv (vlastní data uživatele): trend rostoucí, YoY 3.8 %"));

        String bullets = ExploreSectionBucketService.sectionContextBullets(
                items, 9000, ExploreUserDataPrivacy.STRICT_PRIVATE);

        assertThat(bullets).contains("HDP");
        assertThat(bullets).doesNotContain("trzby.csv");
        assertThat(bullets).doesNotContain("21.8");
    }

    @Test
    void safeSummaryShowsMaskedTrendWithoutAbsoluteValue() {
        List<Map<String, Object>> items = List.of(
                uploadRow(
                        "trzby.csv",
                        "trzby.csv [user_upload/upload-1]: poslední hodnota 21.8 (2025-06), trend rostoucí, YoY 3.8 %",
                        "trzby.csv (vlastní data uživatele): trend rostoucí, YoY 3.8 %"));

        String bullets = ExploreSectionBucketService.sectionContextBullets(
                items, 9000, ExploreUserDataPrivacy.SAFE_SUMMARY);

        assertThat(bullets).contains("trzby.csv");
        assertThat(bullets).contains("trend rostoucí");
        assertThat(bullets).contains("YoY 3.8");
        assertThat(bullets).doesNotContain("21.8");
    }

    @Test
    void uploadedRowWithoutMaskedVariantIsOmittedRatherThanLeakingRaw() {
        List<Map<String, Object>> items =
                List.of(Map.of("source_type", "user_upload", "title", "trzby.csv", "data_context_line", "raw 21.8"));

        String bullets = ExploreSectionBucketService.sectionContextBullets(
                items, 9000, ExploreUserDataPrivacy.SAFE_SUMMARY);

        assertThat(bullets).doesNotContain("21.8");
    }
}
