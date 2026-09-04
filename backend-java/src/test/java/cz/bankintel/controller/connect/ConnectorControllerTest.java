package cz.bankintel.controller.connect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import cz.bankintel.domain.dto.ApiKeyDtos.ConnectorWidgetCreateRequest;
import cz.bankintel.domain.dto.ApiKeyDtos.ConnectorWidgetPushRequest;
import cz.bankintel.domain.dto.MeDtos.DashboardWidgetCreateRequest;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.security.CurrentUser;
import cz.bankintel.service.dashboard.DashboardShareService;
import cz.bankintel.service.me.MeDashboardService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConnectorControllerTest {

    @Mock private CurrentUser currentUser;
    @Mock private MeDashboardService meDashboardService;
    @Mock private DashboardShareService dashboardShareService;

    private ConnectorController controller;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        controller = new ConnectorController(currentUser, meDashboardService, dashboardShareService);
        user = new UserEntity();
        user.setId("user-1");
    }

    @Test
    void createWidgetAlwaysUsesTheApiPushChartTypeRegardlessOfCallerInput() {
        when(currentUser.requireUserEntity()).thenReturn(user);
        when(meDashboardService.createWidget(eq(user), eq("page-1"), any()))
                .thenReturn(Map.of("id", "widget-1"));
        when(meDashboardService.pushWidgetData(eq(user), eq("widget-1"), any()))
                .thenReturn(Map.of("id", "widget-1", "data_snapshot", Map.of("data", "rows")));

        Map<String, Object> result = controller.createWidget(
                "page-1", new ConnectorWidgetCreateRequest("Revenue", "desc", "half", List.of(1, 2, 3)));

        var captor = org.mockito.ArgumentCaptor.forClass(DashboardWidgetCreateRequest.class);
        verify(meDashboardService).createWidget(eq(user), eq("page-1"), captor.capture());
        assertThat(captor.getValue().type()).isEqualTo("api_push_chart");
        assertThat(captor.getValue().title()).isEqualTo("Revenue");
        verify(meDashboardService).pushWidgetData(user, "widget-1", List.of(1, 2, 3));
        assertThat(result.get("id")).isEqualTo("widget-1");
    }

    @Test
    void createWidgetSkipsThePushCallWhenNoDataIsSupplied() {
        when(currentUser.requireUserEntity()).thenReturn(user);
        when(meDashboardService.createWidget(eq(user), eq("page-1"), any()))
                .thenReturn(Map.of("id", "widget-1"));

        controller.createWidget("page-1", new ConnectorWidgetCreateRequest("Revenue", null, null, null));

        verify(meDashboardService, org.mockito.Mockito.never()).pushWidgetData(any(), any(), any());
    }

    @Test
    void pushWidgetDataDelegatesDirectlyToTheService() {
        when(currentUser.requireUserEntity()).thenReturn(user);

        controller.pushWidgetData("widget-1", new ConnectorWidgetPushRequest(List.of(1)));

        verify(meDashboardService).pushWidgetData(user, "widget-1", List.of(1));
        verifyNoMoreInteractions(dashboardShareService);
    }

    @Test
    void readWidgetTrimsTheFullEmbedPayloadToAStableDataOnlyShape() {
        when(dashboardShareService.embedWidget("tok", "widget-1")).thenReturn(Map.of(
                "ok", true,
                "widget", Map.of(
                        "title", "Revenue",
                        "type", "api_push_chart",
                        "data", Map.of("data", List.of(1, 2)),
                        "last_fetched_at", "2026-09-04T00:00:00Z",
                        "config", Map.of("internal_key", "should-not-leak"))));

        Map<String, Object> result = controller.readWidget("tok", "widget-1");

        assertThat(result.keySet()).containsExactlyInAnyOrder("title", "type", "data", "last_updated_at");
        assertThat(result.get("title")).isEqualTo("Revenue");
        assertThat(result.get("last_updated_at")).isEqualTo("2026-09-04T00:00:00Z");
    }
}
