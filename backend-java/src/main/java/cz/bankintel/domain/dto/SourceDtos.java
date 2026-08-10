package cz.bankintel.domain.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

public final class SourceDtos {

    private SourceDtos() {}

    public record SourceCreateRequest(
            @NotBlank String name,
            @NotBlank @JsonProperty("source_type") String sourceType,
            @JsonProperty("base_url") String baseUrl,
            String endpoint,
            String method,
            @JsonProperty("auth_type") String authType,
            Map<String, Object> credentials,
            Map<String, Object> headers,
            @JsonProperty("query_params") Map<String, Object> queryParams,
            @JsonProperty("refresh_interval_minutes") Integer refreshIntervalMinutes,
            Boolean active,
            @JsonProperty("dataset_name") String datasetName,
            @JsonProperty("allow_experimental_connector") Boolean allowExperimentalConnector) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SourceUpdateRequest(
            String name,
            @JsonProperty("base_url") String baseUrl,
            String endpoint,
            String method,
            @JsonProperty("auth_type") String authType,
            Map<String, Object> credentials,
            Map<String, Object> headers,
            @JsonProperty("query_params") Map<String, Object> queryParams,
            @JsonProperty("refresh_interval_minutes") Integer refreshIntervalMinutes,
            Boolean active,
            @JsonProperty("dataset_name") String datasetName,
            @JsonProperty("allow_experimental_connector") Boolean allowExperimentalConnector) {}

    public record SourceTypesResponse(@NotNull java.util.List<String> types) {}
}
