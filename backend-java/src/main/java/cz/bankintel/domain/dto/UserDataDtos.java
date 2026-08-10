package cz.bankintel.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

public final class UserDataDtos {

    private UserDataDtos() {}

    public record UserSeriesMapRequest(
            String title,
            @JsonProperty("metric_type") String metricType,
            String unit,
            String currency,
            String frequency,
            @JsonProperty("sector_id") String sectorId,
            List<String> tags) {}
}
