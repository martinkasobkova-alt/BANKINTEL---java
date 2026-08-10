package cz.bankintel.service.me;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import cz.bankintel.domain.entity.DashboardPageEntity;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.DashboardPageRepository;
import cz.bankintel.repository.DashboardWidgetRepository;
import cz.bankintel.repository.UserRepository;
import cz.bankintel.service.access.FeatureAccessService;
import cz.bankintel.service.auth.UserMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

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
    @Mock private UserMapper userMapper;
    @Mock private PasswordEncoder passwordEncoder;

    private MeDashboardService service;
    private UserEntity subscriber;

    @BeforeEach
    void setUp() {
        service = new MeDashboardService(
                userRepository, pageRepository, widgetRepository, featureAccessService, userMapper, passwordEncoder);
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
}
