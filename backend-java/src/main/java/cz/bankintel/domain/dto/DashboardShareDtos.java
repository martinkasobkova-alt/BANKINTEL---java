package cz.bankintel.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public final class DashboardShareDtos {

    private DashboardShareDtos() {}

    public record WidgetComparePreviewRequest(
            String token,
            @JsonProperty("page_id") String pageId,
            @JsonProperty("widget_id") String widgetId,
            @JsonProperty("compare_with") List<Map<String, Object>> compareWith) {}
}
