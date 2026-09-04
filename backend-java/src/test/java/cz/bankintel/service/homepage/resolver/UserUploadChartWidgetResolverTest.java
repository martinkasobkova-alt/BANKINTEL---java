package cz.bankintel.service.homepage.resolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.domain.entity.UserUploadEntity;
import cz.bankintel.repository.UserUploadRepository;
import cz.bankintel.search.CatalogPreviewService;
import cz.bankintel.service.myseries.SavedSeriesResolverService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Živě zjištěno: „Srovnat s řadou" na grafu z vlastních dat (primární je nahraný soubor) →
 * přidat katalogovou řadu jako srovnání. Dialog uložení nahlásil úspěch, ale po tvrdém reloadu
 * grafu zůstala jen původní řada - {@code UserUploadChartWidgetResolver} `chart_compare_with`
 * vůbec nečetla. Tenhle test soubor pokrývá opravu - zrcadlo {@link
 * ExternalCatalogChartWidgetResolverTest}'s pokrytí opačného směru.
 */
class UserUploadChartWidgetResolverTest {

    private UserUploadRepository uploadRepository;
    private SavedSeriesResolverService savedSeriesResolverService;
    private CatalogPreviewService catalogPreviewService;
    private DatasetViewResolver datasetViewResolver;
    private UserUploadChartWidgetResolver resolver;

    @BeforeEach
    void setUp() {
        uploadRepository = mock(UserUploadRepository.class);
        savedSeriesResolverService = mock(SavedSeriesResolverService.class);
        catalogPreviewService = mock(CatalogPreviewService.class);
        datasetViewResolver = mock(DatasetViewResolver.class);
        resolver = new UserUploadChartWidgetResolver(
                uploadRepository, savedSeriesResolverService, catalogPreviewService, datasetViewResolver);
    }

    private static UserUploadEntity upload(String id, String userId, String originalName) {
        UserUploadEntity entity = new UserUploadEntity();
        entity.setId(id);
        entity.setUserId(userId);
        entity.setOriginalName(originalName);
        return entity;
    }

    @Test
    void twoArgOverloadWithoutComparisonsBehavesLikeTheSingleArgOne() {
        when(uploadRepository.findById("upload-1")).thenReturn(Optional.of(upload("upload-1", "user-1", "trzby.csv")));
        when(savedSeriesResolverService.resolvePoints(eq("user-1"), any()))
                .thenReturn(new SavedSeriesResolverService.ResolvedPoints(
                        List.of(Map.of("period", "2024-01", "value", 10.0)), Map.of()));
        UserEntity user = new UserEntity();
        user.setId("user-1");

        Map<String, Object> result = resolver.resolve(
                new LinkedHashMap<>(Map.of("user_upload_id", "upload-1", "owner_user_id", "user-1")), user);

        assertThat(result.get("title")).isEqualTo("trzby.csv");
        assertThat(result.get("error")).isNull();
    }

    @Test
    void mergesCatalogSeriesAsComparisonOnUploadPrimaryChart() {
        when(uploadRepository.findById("upload-1")).thenReturn(Optional.of(upload("upload-1", "user-1", "trzby.csv")));
        when(savedSeriesResolverService.resolvePoints(eq("user-1"), any()))
                .thenReturn(new SavedSeriesResolverService.ResolvedPoints(
                        List.of(
                                Map.of("period", "2024-01", "value", 10.0),
                                Map.of("period", "2024-02", "value", 12.0)),
                        Map.of()));
        when(catalogPreviewService.preview(any()))
                .thenReturn(Map.of("rows", List.of(Map.of("date", "2024-01", "value", 100))));
        Map<String, Object> catalogRendered = new LinkedHashMap<>();
        catalogRendered.put("title", "FRED indicator");
        catalogRendered.put("rows", List.of(Map.of("x", "2024-01", "y", 100.0), Map.of("x", "2024-02", "y", 101.0)));
        when(datasetViewResolver.resolveFromRows(any(), any(), any(), any())).thenReturn(catalogRendered);
        UserEntity user = new UserEntity();
        user.setId("user-1");

        Map<String, Object> result = resolver.resolve(
                new LinkedHashMap<>(Map.of(
                        "user_upload_id", "upload-1",
                        "owner_user_id", "user-1",
                        "chart_compare_with", List.of(Map.of(
                                "catalog", "fred",
                                "set_id", "SOME_ID",
                                "name", "FRED indicator")))),
                user);

        assertThat(result.get("compare_requested_count")).isEqualTo(1);
        assertThat(result.get("compare_added_count")).isEqualTo(1);
        assertThat(result.get("multi_series")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("rows");
        assertThat(rows).containsExactly(
                Map.of("period", "2024-01", "s0", 10.0, "s1", 100.0),
                Map.of("period", "2024-02", "s0", 12.0, "s1", 101.0));
    }

    @Test
    void mergesSecondUploadAsComparisonOnUploadPrimaryChart() {
        when(uploadRepository.findById("upload-1")).thenReturn(Optional.of(upload("upload-1", "user-1", "trzby.csv")));
        when(uploadRepository.findById("upload-2")).thenReturn(Optional.of(upload("upload-2", "user-1", "naklady.csv")));
        when(savedSeriesResolverService.resolvePoints(eq("user-1"), any())).thenAnswer(invocation -> {
            Map<String, Object> payload = invocation.getArgument(1);
            if ("upload-2".equals(payload.get("user_upload_id"))) {
                return new SavedSeriesResolverService.ResolvedPoints(
                        List.of(Map.of("period", "2024-01", "value", 5.0)), Map.of());
            }
            return new SavedSeriesResolverService.ResolvedPoints(
                    List.of(Map.of("period", "2024-01", "value", 10.0)), Map.of());
        });
        UserEntity user = new UserEntity();
        user.setId("user-1");

        Map<String, Object> result = resolver.resolve(
                new LinkedHashMap<>(Map.of(
                        "user_upload_id", "upload-1",
                        "owner_user_id", "user-1",
                        "chart_compare_with", List.of(Map.of("user_upload_id", "upload-2", "name", "Náklady")))),
                user);

        assertThat(result.get("compare_added_count")).isEqualTo(1);
        assertThat(result.get("multi_series")).isEqualTo(true);
    }

    @Test
    void reportsCatalogCompareErrorInsteadOfSilentlyDroppingIt() {
        when(uploadRepository.findById("upload-1")).thenReturn(Optional.of(upload("upload-1", "user-1", "trzby.csv")));
        when(savedSeriesResolverService.resolvePoints(eq("user-1"), any()))
                .thenReturn(new SavedSeriesResolverService.ResolvedPoints(
                        List.of(Map.of("period", "2024-01", "value", 10.0)), Map.of()));
        when(catalogPreviewService.preview(any())).thenReturn(Map.of("error", "Series is unavailable"));
        UserEntity user = new UserEntity();
        user.setId("user-1");

        Map<String, Object> result = resolver.resolve(
                new LinkedHashMap<>(Map.of(
                        "user_upload_id", "upload-1",
                        "owner_user_id", "user-1",
                        "chart_compare_with", List.of(Map.of("catalog", "fred", "set_id", "missing")))),
                user);

        assertThat(result.get("compare_added_count")).isEqualTo(0);
        assertThat((List<?>) result.get("compare_errors")).hasSize(1);
        assertThat(result.get("multi_series")).isNull();
    }
}
