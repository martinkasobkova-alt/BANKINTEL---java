package cz.bankintel.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

public final class MySeriesDtos {

    private MySeriesDtos() {}

    public record MySavedSeriesCreateRequest(
            @NotBlank @Size(min = 1, max = 500) String title,
            @Size(max = 200) String source,
            @JsonProperty("source_type") @Size(max = 80) String sourceType,
            @JsonProperty("source_series_id") @Size(max = 500) String sourceSeriesId,
            @JsonProperty("source_dataset_id") @Size(max = 120) String sourceDatasetId,
            @JsonProperty("resolver_payload") Map<String, Object> resolverPayload,
            @Size(max = 120) String unit,
            @Size(max = 80) String frequency,
            @Size(max = 200) String area,
            @Size(max = 500) String category,
            Map<String, Object> metadata) {}
}
