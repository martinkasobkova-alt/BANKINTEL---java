package cz.bankintel.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

public final class AdminDtos {

    private AdminDtos() {}

    public record CreateUserRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8) String password,
            @NotBlank String name,
            String role,
            String company,
            String phone) {}

    public record AdminPatchUserRequest(
            @JsonProperty("access_tier") String accessTier,
            @JsonProperty("has_premium_access") boolean hasPremiumAccess) {}

    public record FeatureAccessLevelUpdate(@JsonProperty("access_level") String accessLevel) {}

    public record SetSubscriberRegistrationCodeRequest(
            @JsonProperty("registration_code") @NotBlank @Size(min = 6) String registrationCode) {}

    public record BugReportAdminPatch(String status) {}

    public record FormulaCreateRequest(
            @NotBlank String name,
            @NotBlank String expression,
            @JsonProperty("group_by") List<String> groupBy,
            List<String> datasets,
            String description) {}

    public record ComputedIndicatorCreateRequest(
            @NotBlank String name,
            @NotBlank String operation,
            Map<String, Object> left,
            Map<String, Object> right,
            List<Map<String, Object>> series,
            String description,
            String unit,
            Map<String, Object> options) {}
}
