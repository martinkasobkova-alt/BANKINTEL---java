package cz.bankintel.service.me;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cz.bankintel.domain.entity.DashboardPageEntity;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.DashboardPageRepository;
import cz.bankintel.repository.DashboardWidgetRepository;
import cz.bankintel.repository.UserRepository;
import cz.bankintel.service.access.FeatureAccessService;
import cz.bankintel.service.auth.UserMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

/**
 * Regression test for GET /api/me/dashboard/default returning HTTP 500 for a user without any
 * dashboard pages: {@code Map.of("page", null, "widgets", List.of())} throws
 * {@link NullPointerException} because {@code Map.of} rejects null values.
 */
@ExtendWith(MockitoExtension.class)
class MeDashboardServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private DashboardPageRepository pageRepository;
    @Mock private DashboardWidgetRepository widgetRepository;
    @Mock private FeatureAccessService featureAccessService;
    @Mock private cz.bankintel.service.homepage.HomepageHeadlineKpiService headlineKpiService;
    @Mock private UserMapper userMapper;
    @Mock private PasswordEncoder passwordEncoder;

    private MeDashboardService service;
    private UserEntity subscriber;

    @BeforeEach
    void setUp() {
        service = new MeDashboardService(
                userRepository,
                pageRepository,
                widgetRepository,
                featureAccessService,
                headlineKpiService,
                userMapper,
                passwordEncoder);
        subscriber = new UserEntity();
        subscriber.setId("user-1");
        subscriber.setRole("subscriber");
        subscriber.setHasPremiumAccess(true);
    }

    @Test
    void getDefaultDashboardReturnsNullPageAndEmptyWidgetsForUserWithoutPages() {
        when(pageRepository.findFirstByUserIdAndDefaultPageTrue("user-1")).thenReturn(Optional.empty());
        when(pageRepository.findByUserIdOrderBySortOrderAsc("user-1")).thenReturn(List.of());

        Map<String, Object> result =
                assertDoesNotThrow(() -> service.getDefaultDashboard(subscriber));

        assertTrue(result.containsKey("page"));
        assertNull(result.get("page"));
        assertTrue(result.get("widgets") instanceof List<?>);
        assertTrue(((List<?>) result.get("widgets")).isEmpty());
    }

    @Test
    void getDefaultDashboardReturnsPageAndWidgetsWhenPageExists() {
        DashboardPageEntity page = new DashboardPageEntity();
        page.setId("page-1");
        page.setUserId("user-1");
        page.setTitle("Moje stránka");
        page.setAllowedUserIds(List.of());
        when(pageRepository.findFirstByUserIdAndDefaultPageTrue("user-1")).thenReturn(Optional.of(page));
        when(widgetRepository.findByUserIdAndPageIdOrderBySortOrderAsc(anyString(), anyString())).thenReturn(List.of());

        Map<String, Object> result =
                assertDoesNotThrow(() -> service.getDefaultDashboard(subscriber));

        assertTrue(result.get("page") instanceof Map<?, ?>);
        assertTrue(((List<?>) result.get("widgets")).isEmpty());
    }

    /* ── KPI dlaždice osobní stránky ────────────────────────────────────── */

    private DashboardPageEntity ownedPage() {
        DashboardPageEntity page = new DashboardPageEntity();
        page.setId("page-1");
        page.setUserId("user-1");
        page.setTitle("Ceny");
        page.setAllowedUserIds(List.of());
        return page;
    }

    @Test
    void savePageKpisUlozeneDlazdiceVratiIZapiseNaStranku() {
        DashboardPageEntity page = ownedPage();
        when(pageRepository.findByIdAndUserId("page-1", "user-1")).thenReturn(Optional.of(page));

        List<Map<String, Object>> kpis = List.of(Map.of("id", "k1", "title", "Inflace"));
        List<Map<String, Object>> saved = service.savePageKpis(subscriber, "page-1", kpis);

        assertEquals(1, saved.size());
        assertEquals(kpis, page.getHeadlineKpis());
        verify(pageRepository).save(page);
    }

    @Test
    void savePageKpisOdmitneVicNezOsmDlazdic() {
        when(pageRepository.findByIdAndUserId("page-1", "user-1")).thenReturn(Optional.of(ownedPage()));

        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            tooMany.add(Map.of("id", "k" + i));
        }

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class, () -> service.savePageKpis(subscriber, "page-1", tooMany));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(pageRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void kpiCiziStrankyNejsouDostupne() {
        when(pageRepository.findByIdAndUserId("page-cizi", "user-1")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class, () -> service.listPageKpis(subscriber, "page-cizi"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void resolvePageKpisPouzijeStejnyResolverJakoVerejnyPrehled() {
        DashboardPageEntity page = ownedPage();
        page.setHeadlineKpis(List.of(Map.of("id", "k1")));
        when(pageRepository.findByIdAndUserId("page-1", "user-1")).thenReturn(Optional.of(page));
        when(headlineKpiService.resolveList(page.getHeadlineKpis(), subscriber))
                .thenReturn(Map.of("kpis", List.of(Map.of("id", "k1", "value", 155.0))));

        Map<String, Object> out = service.resolvePageKpis(subscriber, "page-1");

        assertTrue(out.get("kpis") instanceof List<?>);
        verify(headlineKpiService).resolveList(page.getHeadlineKpis(), subscriber);
    }
}
