package cz.bankintel.service.calculations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cz.bankintel.repository.UserSavedSeriesRepository;
import cz.bankintel.search.analytics.AnalyticsSeriesLoader;
import cz.bankintel.service.myseries.SavedSeriesResolverService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SeriesOperandLoaderTest {

    @Mock private SavedSeriesResolverService savedSeriesResolverService;
    @Mock private UserSavedSeriesRepository userSavedSeriesRepository;
    @Mock private AnalyticsSeriesLoader analyticsSeriesLoader;

    private SeriesOperandLoader loader;

    @BeforeEach
    void setUp() {
        loader = new SeriesOperandLoader(savedSeriesResolverService, userSavedSeriesRepository, analyticsSeriesLoader);
    }

    @Test
    void catalogReferenceUsesLivePreviewLoaderInsteadOfSavedSourceUuid() {
        Map<String, Double> values = Map.of("2024", 2.4, "2025", 2.1);
        when(analyticsSeriesLoader.loadStrict(
                        eq("imf"),
                        eq("USA.PCPIPCH"),
                        eq("Inflace USA"),
                        eq("US"),
                        eq(Map.of("frequency", "Y")),
                        eq(Map.of("measure", "pct")),
                        eq("headline"),
                        eq(List.of("headline")),
                        eq("primary")))
                .thenReturn(new AnalyticsSeriesLoader.LoadedSeries(null, values, null));

        Map<String, Double> result = loader.loadSeriesMap(Map.of(
                "source_type", "imf",
                "source_id", "imf",
                "set_id", "USA.PCPIPCH",
                "title", "Inflace USA",
                "geo", "US",
                "query_params", Map.of("frequency", "Y"),
                "dimension_filters", Map.of("measure", "pct"),
                "selected_indicator", "headline",
                "selected_indicators", List.of("headline"),
                "role", "primary"), "user-1");

        assertEquals(values, result);
        verify(savedSeriesResolverService, never()).resolvePoints(anyString(), any());
    }

    @Test
    void emptyCatalogSeriesDoesNotFallThroughToSavedSourceLoader() {
        when(analyticsSeriesLoader.loadStrict(
                        eq("eurostat"),
                        eq("missing-data"),
                        eq("Prázdná řada"),
                        eq("CZ"),
                        eq(Map.of()),
                        eq(Map.of()),
                        eq(""),
                        eq(List.of()),
                        eq("")))
                .thenReturn(new AnalyticsSeriesLoader.LoadedSeries(null, Map.of(), null));

        Map<String, Double> result = loader.loadSeriesMap(Map.of(
                "source_type", "eurostat",
                "source_id", "eurostat",
                "set_id", "missing-data",
                "title", "Prázdná řada",
                "geo", "CZ"), "user-1");

        assertEquals(Map.of(), result);
        verify(savedSeriesResolverService, never()).resolvePoints(anyString(), any());
    }
}
