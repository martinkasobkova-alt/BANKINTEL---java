package cz.bankintel.explore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cz.bankintel.connector.ConnectorFactory;
import cz.bankintel.domain.entity.UserUploadEntity;
import cz.bankintel.explore.ExploreDtos.ExploreSummarizeRequest;
import cz.bankintel.explore.manager.ManagerSeriesCacheReader;
import cz.bankintel.explore.manager.fetch.ManagerFetchRegistry;
import cz.bankintel.repository.UserUploadRepository;
import cz.bankintel.search.CatalogIndexStore;
import cz.bankintel.search.CatalogPreviewOrchestrator;
import cz.bankintel.service.myseries.SavedSeriesResolverService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Živě zjištěno: `ExploreInstantThenDetailService`/`ExploreSummarizeService` obě nezávisle
 * nikdy nečetly `request.uploadIds()`/`request.includeUserData()` - vybraná nahraná data se do
 * detailní Explorer analýzy nedostala vůbec. Pokrývá opravu: {@code fetchUserUpload} (resolvuje
 * jednu nahranou řadu do stejného tvaru jako katalogovou) a {@code appendUserUploadedSeries}
 * (rozhoduje, kdy vůbec zkusit).
 */
class ExploreSummarizeFetchServiceUserUploadTest {

    private UserUploadRepository uploadRepository;
    private SavedSeriesResolverService savedSeriesResolverService;
    private ExploreSummarizeFetchService service;

    @BeforeEach
    void setUp() {
        uploadRepository = mock(UserUploadRepository.class);
        savedSeriesResolverService = mock(SavedSeriesResolverService.class);
        service = new ExploreSummarizeFetchService(
                mock(CatalogIndexStore.class),
                mock(CatalogPreviewOrchestrator.class),
                mock(ConnectorFactory.class),
                mock(ManagerSeriesCacheReader.class),
                mock(ManagerFetchRegistry.class),
                uploadRepository,
                savedSeriesResolverService);
    }

    private static UserUploadEntity upload(String id, String userId, String originalName) {
        UserUploadEntity entity = new UserUploadEntity();
        entity.setId(id);
        entity.setUserId(userId);
        entity.setOriginalName(originalName);
        return entity;
    }

    private static ExploreSummarizeRequest request(boolean includeUserData, List<String> uploadIds, String privacyMode) {
        return new ExploreSummarizeRequest(
                "Jak si vede firma?", "banking_finance", "CZ", null, null, null, null, null,
                List.of(), includeUserData, uploadIds, privacyMode, false, "standard");
    }

    @Test
    void fetchUserUploadBuildsALoadedItemShapedLikeACatalogRowWithAMaskedSafeSummaryVariant() {
        when(uploadRepository.findByIdAndUserId("upload-1", "user-1"))
                .thenReturn(Optional.of(upload("upload-1", "user-1", "trzby.csv")));
        when(savedSeriesResolverService.resolvePoints(eq("user-1"), any()))
                .thenReturn(new SavedSeriesResolverService.ResolvedPoints(
                        List.of(
                                Map.of("period", "2025-05", "value", 21.0),
                                Map.of("period", "2025-06", "value", 21.8)),
                        Map.of()));

        Optional<Map<String, Object>> result = service.fetchUserUpload("upload-1", "user-1");

        assertThat(result).isPresent();
        Map<String, Object> row = result.get();
        assertThat(row.get("title")).isEqualTo("trzby.csv");
        assertThat(row.get("source_type")).isEqualTo("user_upload");
        assertThat(row.get("status")).isEqualTo("loaded");
        assertThat(String.valueOf(row.get("data_context_line"))).contains("21.8");
        assertThat(String.valueOf(row.get("data_context_line_safe_summary")))
                .contains("trend")
                .doesNotContain("21.8");
    }

    @Test
    void fetchUserUploadReturnsEmptyWhenUploadDoesNotBelongToOwner() {
        when(uploadRepository.findByIdAndUserId("upload-1", "user-1")).thenReturn(Optional.empty());

        assertThat(service.fetchUserUpload("upload-1", "user-1")).isEmpty();
    }

    @Test
    void fetchUserUploadReturnsEmptyWithFewerThanTwoPoints() {
        when(uploadRepository.findByIdAndUserId("upload-1", "user-1"))
                .thenReturn(Optional.of(upload("upload-1", "user-1", "trzby.csv")));
        when(savedSeriesResolverService.resolvePoints(eq("user-1"), any()))
                .thenReturn(new SavedSeriesResolverService.ResolvedPoints(
                        List.of(Map.of("period", "2025-06", "value", 21.8)), Map.of()));

        assertThat(service.fetchUserUpload("upload-1", "user-1")).isEmpty();
    }

    @Test
    void appendUserUploadedSeriesLeavesLoadedUnchangedWhenIncludeUserDataIsOff() {
        List<Map<String, Object>> loaded = List.of(Map.of("title", "GDP"));
        ExploreSummarizeRequest req = request(false, List.of("upload-1"), "strict_private");

        List<Map<String, Object>> result = service.appendUserUploadedSeries(loaded, req, "user-1");

        assertThat(result).isEqualTo(loaded);
        verify(uploadRepository, never()).findByIdAndUserId(any(), any());
    }

    @Test
    void appendUserUploadedSeriesLeavesLoadedUnchangedWithoutAnOwner() {
        List<Map<String, Object>> loaded = List.of(Map.of("title", "GDP"));
        ExploreSummarizeRequest req = request(true, List.of("upload-1"), "strict_private");

        List<Map<String, Object>> result = service.appendUserUploadedSeries(loaded, req, "");

        assertThat(result).isEqualTo(loaded);
        verify(uploadRepository, never()).findByIdAndUserId(any(), any());
    }

    @Test
    void appendUserUploadedSeriesAppendsResolvedUploadRowsWhenEnabled() {
        when(uploadRepository.findByIdAndUserId("upload-1", "user-1"))
                .thenReturn(Optional.of(upload("upload-1", "user-1", "trzby.csv")));
        when(savedSeriesResolverService.resolvePoints(eq("user-1"), any()))
                .thenReturn(new SavedSeriesResolverService.ResolvedPoints(
                        List.of(
                                Map.of("period", "2025-05", "value", 21.0),
                                Map.of("period", "2025-06", "value", 21.8)),
                        Map.of()));
        List<Map<String, Object>> loaded = List.of(Map.of("title", "GDP", "source_type", "eurostat"));
        ExploreSummarizeRequest req = request(true, List.of("upload-1"), "strict_private");

        List<Map<String, Object>> result = service.appendUserUploadedSeries(loaded, req, "user-1");

        assertThat(result).hasSize(2);
        assertThat(result.get(1).get("title")).isEqualTo("trzby.csv");
        assertThat(loaded).hasSize(1);
    }
}
