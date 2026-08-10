package cz.bankintel.domain.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {}

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {}

    public record RegisterRequest(
            @NotBlank String company,
            @NotBlank String name,
            @NotBlank @Email String email,
            @NotBlank String phone,
            @NotBlank @Size(min = 8) String password,
            @JsonProperty("registration_code") String registrationCode,
            @JsonProperty("captcha_token") String captchaToken,
            @JsonProperty("turnstile_token") String turnstileToken) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MeResponse(
            String id,
            String company,
            String email,
            String name,
            String phone,
            String role,
            @JsonProperty("access_tier") String accessTier,
            @JsonProperty("has_premium_access") boolean hasPremiumAccess,
            @JsonProperty("premium_access_granted_at") String premiumAccessGrantedAt,
            @JsonProperty("premium_access_source") String premiumAccessSource,
            @JsonProperty("is_subscriber") boolean isSubscriber,
            @JsonProperty("open_personal_dashboard_on_login") boolean openPersonalDashboardOnLogin,
            @JsonProperty("default_dashboard_page_id") String defaultDashboardPageId,
            @JsonProperty("email_verified") boolean emailVerified) {}
}
