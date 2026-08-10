package cz.bankintel.domain.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

public final class MeDtos {

    private MeDtos() {}

    public record DashboardPageCreateRequest(
            @NotBlank @Size(min = 1, max = 200) String title) {}

    public record DashboardPagePatchRequest(
            @Size(min = 1, max = 200) String title,
            Integer order,
            @JsonProperty("is_default") Boolean isDefault,
            @JsonProperty("access_mode") String accessMode,
            @JsonProperty("allowed_user_ids") List<String> allowedUserIds,
            @JsonProperty("share_enabled") Boolean shareEnabled,
            @JsonProperty("regenerate_share_token") Boolean regenerateShareToken,
            @JsonProperty("allow_viewer_compare") Boolean allowViewerCompare,
            @JsonProperty("allow_embed") Boolean allowEmbed) {}

    public record DashboardWidgetCreateRequest(
            @NotBlank String type,
            @Size(max = 500) String title,
            @Size(max = 8000) String description,
            Map<String, Object> config,
            String width) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DashboardWidgetPatchRequest {
        @Size(max = 500)
        private String title;

        @Size(max = 8000)
        private String description;

        private Map<String, Object> config;
        private Integer order;
        private String width;

        @JsonProperty("page_id")
        private String pageId;

        private Integer rowSpan;
        private boolean rowSpanPresent;

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public Map<String, Object> getConfig() {
            return config;
        }

        public void setConfig(Map<String, Object> config) {
            this.config = config;
        }

        public Integer getOrder() {
            return order;
        }

        public void setOrder(Integer order) {
            this.order = order;
        }

        public String getWidth() {
            return width;
        }

        public void setWidth(String width) {
            this.width = width;
        }

        public String getPageId() {
            return pageId;
        }

        public void setPageId(String pageId) {
            this.pageId = pageId;
        }

        public boolean isRowSpanPresent() {
            return rowSpanPresent;
        }

        public Integer getRowSpan() {
            return rowSpan;
        }

        @JsonProperty("rowSpan")
        @JsonAlias("row_span")
        public void setRowSpan(Integer rowSpan) {
            this.rowSpan = rowSpan;
            this.rowSpanPresent = true;
        }
    }

    public record ReorderPagesRequest(@NotEmpty @JsonProperty("page_ids") List<String> pageIds) {}

    public record ReorderWidgetsRequest(
            @NotBlank @JsonProperty("page_id") String pageId,
            @NotEmpty @JsonProperty("widget_ids") List<String> widgetIds,
            @JsonProperty("widget_layout") Map<String, Map<String, Object>> widgetLayout) {}

    public record PreferencesPatchRequest(
            @JsonProperty("open_personal_dashboard_on_login") Boolean openPersonalDashboardOnLogin,
            @JsonProperty("default_dashboard_page_id") String defaultDashboardPageId) {}

    public record ProfilePatchRequest(
            @Size(min = 1, max = 160) String name, @Size(max = 200) String company, @Size(max = 80) String phone) {}

    public record ChangePasswordRequest(
            @NotBlank @JsonProperty("current_password") String currentPassword,
            @NotBlank @Size(min = 8) @JsonProperty("new_password") String newPassword) {}

    public record NavOrderPutRequest(@NotNull @NotEmpty List<String> order) {}

    public record RenderWidgetsRequest(
            @NotEmpty List<String> ids,
            @JsonProperty("force_refresh_ids") List<String> forceRefreshIds) {}

    public static Map<String, Boolean> okMap() {
        return Map.of("ok", true);
    }
}
