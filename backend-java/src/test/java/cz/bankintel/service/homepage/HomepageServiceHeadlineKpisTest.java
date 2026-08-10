package cz.bankintel.service.homepage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import cz.bankintel.domain.entity.HomepageConfigEntity;
import cz.bankintel.repository.AppSettingsRepository;
import cz.bankintel.repository.HomepageConfigRepository;
import cz.bankintel.repository.HomepageWidgetRepository;
import cz.bankintel.repository.SectionRepository;
import cz.bankintel.repository.SectionWidgetRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Regression test for the ported {@code PUT /api/homepage/kpis} behaviour (homepage_routes.py, ř.
 * 1128): saves headline KPIs, assigning ids and capping title length like the Python
 * {@code HeadlineKpi} model + {@code normalize_kpi_localized}.
 */
@ExtendWith(MockitoExtension.class)
class HomepageServiceHeadlineKpisTest {

    @Mock
    private HomepageConfigRepository configRepository;

    @Mock
    private HomepageWidgetRepository widgetRepository;

    @Mock
    private SectionRepository sectionRepository;

    @Mock
    private SectionWidgetRepository sectionWidgetRepository;

    @Mock
    private AppSettingsRepository appSettingsRepository;

    @Mock
    private WidgetRenderService widgetRenderService;

    private HomepageService service;

    @BeforeEach
    void setUp() {
        service = new HomepageService(
                configRepository,
                widgetRepository,
                sectionRepository,
                sectionWidgetRepository,
                appSettingsRepository,
                widgetRenderService);
        when(configRepository.findById("main")).thenReturn(Optional.of(new HomepageConfigEntity()));
        when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void updateHeadlineKpisAssignsIdAndCapsTitleLength() {
        String longTitle = "x".repeat(250);
        Map<String, Object> kpiWithoutId = Map.of("title", longTitle, "type", "arad_view", "config", Map.of("a", 1));

        Map<String, Object> result =
                assertDoesNotThrow(() -> service.updateHeadlineKpis(List.of(kpiWithoutId)));

        assertEquals(true, result.get("ok"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> kpis = (List<Map<String, Object>>) result.get("kpis");
        assertEquals(1, kpis.size());
        Map<String, Object> saved = kpis.get(0);
        assertNotNull(saved.get("id"));
        assertFalse(String.valueOf(saved.get("id")).isBlank());
        assertEquals(200, String.valueOf(saved.get("title")).length());
        assertEquals("arad_view", saved.get("type"));
    }

    @Test
    void updateHeadlineKpisKeepsExistingIdAndDefaultsMissingFields() {
        Map<String, Object> kpi = Map.of("id", "kpi-existing", "title", "HDP");

        Map<String, Object> result = service.updateHeadlineKpis(List.of(kpi));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> kpis = (List<Map<String, Object>>) result.get("kpis");
        Map<String, Object> saved = kpis.get(0);
        assertEquals("kpi-existing", saved.get("id"));
        assertEquals("HDP", saved.get("title"));
        assertEquals("arad_view", saved.get("type"));
        assertTrue(saved.get("config") instanceof Map<?, ?>);
    }

    @Test
    void updateHeadlineKpisHandlesEmptyListWithoutThrowing() {
        Map<String, Object> result = assertDoesNotThrow(() -> service.updateHeadlineKpis(List.of()));
        assertEquals(true, result.get("ok"));
        assertTrue(((List<?>) result.get("kpis")).isEmpty());
    }
}
