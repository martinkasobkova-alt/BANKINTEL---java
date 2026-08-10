package cz.bankintel.controller.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import cz.bankintel.domain.dto.AuthDtos.LoginRequest;
import cz.bankintel.domain.dto.AuthDtos.RegisterRequest;
import cz.bankintel.security.AuthCookieService;
import cz.bankintel.security.CurrentUser;
import cz.bankintel.security.JwtAuthFilter;
import cz.bankintel.security.JwtService;
import cz.bankintel.service.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthService authService;
    private final AuthCookieService authCookieService;
    private final CurrentUser currentUser;

    public AuthController(AuthService authService, AuthCookieService authCookieService, CurrentUser currentUser) {
        this.authService = authService;
        this.authCookieService = authCookieService;
        this.currentUser = currentUser;
    }

    @PostMapping("/login")
    public Map<String, Object> login(
            @Valid @RequestBody LoginRequest body,
            @RequestHeader(value = "X-Bankoapp-Client", required = false) String client,
            HttpServletResponse response) {
        var result = authService.login(body.email(), body.password());
        return authResponse(result.accessToken(), result.refreshToken(), result.user(), client, response);
    }

    @GetMapping("/me")
    public Object me() {
        var user = currentUser.optionalUser();
        if (user == null) {
            return null;
        }
        return authService.me(user.id());
    }

    @PostMapping("/logout")
    public Map<String, Boolean> logout(HttpServletResponse response) {
        authCookieService.clearAuthCookies(response);
        return Map.of("ok", true);
    }

    @PostMapping("/register")
    public Map<String, Object> register(@Valid @RequestBody RegisterRequest body) {
        var result = authService.register(
                body.company(),
                body.name(),
                body.email(),
                body.phone(),
                body.password(),
                body.registrationCode(),
                turnstileToken(body.captchaToken(), body.turnstileToken()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", result.status());
        out.put("message", result.message());
        out.put("email", result.email());
        return out;
    }

    @PostMapping("/verify-email")
    public AuthService.MessageResult verifyEmailPost(@Valid @RequestBody TokenBody body) {
        return authService.verifyEmail(body.token());
    }

    @GetMapping("/verify-email")
    public Map<String, String> verifyEmailGet(@RequestParam String token) {
        authService.verifyEmail(token);
        return Map.of("ok", "true");
    }

    @PostMapping("/resend-verification")
    public AuthService.MessageResult resendVerification(@Valid @RequestBody EmailBody body) {
        return authService.resendVerification(body.email(), turnstileToken(body.captchaToken(), body.turnstileToken()));
    }

    @PostMapping("/forgot-password")
    public AuthService.MessageResult forgotPassword(@Valid @RequestBody EmailBody body) {
        return authService.forgotPassword(body.email(), turnstileToken(body.captchaToken(), body.turnstileToken()));
    }

    @PostMapping("/reset-password")
    public AuthService.MessageResult resetPassword(@Valid @RequestBody ResetPasswordBody body) {
        return authService.resetPassword(body.token(), body.newPassword());
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh(
            @RequestHeader(value = "X-Bankoapp-Client", required = false) String client,
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestBody(required = false) RefreshBody body) {
        String refresh = JwtAuthFilter.readCookie(request, JwtService.REFRESH_COOKIE)
                .orElseGet(() -> isMobile(client) && body != null ? body.refreshToken() : null);
        if (refresh == null || refresh.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No refresh token");
        }
        var result = authService.refresh(refresh);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        if (isMobile(client)) {
            out.put("access_token", result.accessToken());
            out.put("refresh_token", result.refreshToken());
            out.put("token_type", "bearer");
        } else {
            authCookieService.setAuthCookies(response, result.accessToken(), result.refreshToken());
            authCookieService.setCsrfCookie(response, newCsrfToken());
        }
        return out;
    }

    private Map<String, Object> authResponse(
            String access, String refresh, Object user, String client, HttpServletResponse response) {
        if (!isMobile(client)) {
            authCookieService.setAuthCookies(response, access, refresh);
            authCookieService.setCsrfCookie(response, newCsrfToken());
            return Map.of("user", user);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("access_token", access);
        out.put("refresh_token", refresh);
        out.put("token_type", "bearer");
        out.putAll(Map.of("user", user));
        return out;
    }

    private static boolean isMobile(String client) {
        return "mobile".equalsIgnoreCase(client != null ? client.strip() : "");
    }

    private static String newCsrfToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record TokenBody(@NotBlank String token) {}

    public record EmailBody(
            @NotBlank @Email String email,
            @JsonProperty("captcha_token") String captchaToken,
            @JsonProperty("turnstile_token") String turnstileToken) {}

    private static String turnstileToken(String captchaToken, String turnstileToken) {
        if (turnstileToken != null && !turnstileToken.isBlank()) {
            return turnstileToken.strip();
        }
        return captchaToken != null ? captchaToken.strip() : "";
    }

    public record ResetPasswordBody(@NotBlank String token, @NotBlank @Size(min = 8) @JsonProperty("new_password") String newPassword) {}

    public record RefreshBody(@JsonProperty("refresh_token") String refreshToken) {}
}
