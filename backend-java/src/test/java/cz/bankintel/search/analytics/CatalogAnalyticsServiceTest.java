package cz.bankintel.search.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import cz.bankintel.search.CatalogIndexStore;
import cz.bankintel.search.forecast.CatalogForecastService;
import cz.bankintel.search.forecast.ForecastPlannerService;
import cz.bankintel.search.forecast.ForecastSeriesNormalizer;
import cz.bankintel.service.timeseries.AnomalySeriesDetector;
import cz.bankintel.service.timeseries.RealValuesAnalyticsService;
import cz.bankintel.service.timeseries.SeriesComparisonService;
import cz.bankintel.service.timeseries.SeriesCompatibilityGuard;
import cz.bankintel.service.timeseries.TimeSeriesMetricsService;
import cz.bankintel.service.timeseries.TrendAnalyticsService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CatalogAnalyticsServiceTest {

    @Mock private CatalogIndexStore indexStore;
    @Mock private AnalyticsPlannerService plannerService;
    @Mock private AnalyticsSeriesLoader seriesLoader;
    @Mock private TimeSeriesMetricsService metricsService;
    @Mock private TrendAnalyticsService trendAnalyticsService;
    @Mock private SeriesComparisonService comparisonService;
    @Mock private AnomalySeriesDetector anomalyDetector;
    @Mock private RealValuesAnalyticsService realValuesService;
    @Mock private AnalyticsNarrativeService narrativeService;
    @Mock private CatalogForecastService forecastService;

    private CatalogAnalyticsService service;

    @BeforeEach
    void setUp() {
        service = new CatalogAnalyticsService(
                indexStore,
                plannerService,
                seriesLoader,
                metricsService,
                trendAnalyticsService,
                comparisonService,
                anomalyDetector,
                realValuesService,
                new SeriesCompatibilityGuard(),
                narrativeService,
                forecastService);
    }

    @Test
    void notReliableSeriesWithUnknownDomainReturnsJsonInsteadOfThrowingOnNullDomain() {
        when(indexStore.lookupRow("csu", "CEN0402T03")).thenReturn(Optional.empty());
        AnalyticsPlannerService.PlanResult plan = new AnalyticsPlannerService.PlanResult(
                Optional.empty(), Optional.empty(), List.of("basic_metrics", "trend", "anomalies"), null, "", List.of());
        when(plannerService.plan("Ceny nemovitostí podle velikosti obce", "csu", "CEN0402T03", "CZ", true))
                .thenReturn(plan);

        ForecastSeriesNormalizer.NormalizedSeries normalized = new ForecastSeriesNormalizer.NormalizedSeries(
                "csu:CEN0402T03",
                "Ceny nemovitostí podle velikosti obce",
                "csu",
                "CZ",
                null,
                "Y",
                null,
                List.of(Map.of("date", "2023", "value", 48320.0)),
                1);
        AnalyticsSeriesLoader.LoadedSeries loaded = new AnalyticsSeriesLoader.LoadedSeries(
                normalized,
                Map.of("2023", 48320.0),
                new SeriesCompatibilityGuard.SeriesMetadata("Y", "CZ", null, null, null, null));
        when(seriesLoader.load(
                        "csu",
                        "CEN0402T03",
                        "Ceny nemovitostí podle velikosti obce",
                        "CZ",
                        Map.of(),
                        Map.of(),
                        "",
                        List.of(),
                        null))
                .thenReturn(loaded);

        Map<String, Object> out = service.analyze(Map.of(
                "source_type", "csu",
                "set_id", "CEN0402T03",
                "name", "Ceny nemovitostí podle velikosti obce",
                "geo", "CZ"));

        assertEquals("not_reliable", out.get("quality_status"));
        assertEquals("general", out.get("analysis_type"));
        assertTrue(out.get("planner") instanceof Map<?, ?>);
        assertTrue(out.get("narrative") instanceof Map<?, ?>);
        assertTrue(out.get("methodology_sections") instanceof List<?>);
        assertTrue(String.valueOf(out.get("executive_summary")).contains("nelze spolehliv"));
    }
}
