package cz.bankintel.domain.dto;

import jakarta.validation.constraints.Size;
import java.util.List;

public final class ApiKeyDtos {

    private ApiKeyDtos() {}

    public record ApiKeyCreateRequest(@Size(max = 200) String label, List<String> scopes) {}

    public record ConnectorWidgetCreateRequest(
            @Size(max = 500) String title,
            @Size(max = 8000) String description,
            String width,
            Object data) {}

    public record ConnectorWidgetPushRequest(Object data) {}
}
