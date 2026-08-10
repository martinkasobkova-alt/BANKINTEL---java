package cz.bankintel.service.captcha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.util.BankIntelEnvVars;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Cloudflare Turnstile verification (optional; dev bypass via env). */
@Service
public class TurnstileService {

    private static final Logger log = LoggerFactory.getLogger(TurnstileService.class);
    private static final URI TURNSTILE_VERIFY = URI.create("https://challenges.cloudflare.com/turnstile/v0/siteverify");

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public boolean isProductionEnvironment() {
        String env = BankIntelEnvVars.get("ENVIRONMENT").toLowerCase();
        return "production".equals(env) || "prod".equals(env);
    }

    public boolean captchaBypassEnabled() {
        if (isProductionEnvironment()) {
            return false;
        }
        return BankIntelEnvVars.isTruthy("CAPTCHA_BYPASS");
    }

    public boolean turnstileSecretConfigured() {
        return !BankIntelEnvVars.get("TURNSTILE_SECRET_KEY").isBlank();
    }

    public void requireTurnstileOrBypass(String captchaToken, String feature) {
        if (captchaBypassEnabled()) {
            return;
        }
        if (!turnstileSecretConfigured()) {
            if (isProductionEnvironment()) {
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Ochranná captcha není na serveru nakonfigurovaná (TURNSTILE_SECRET_KEY).");
            }
            return;
        }
        String raw = captchaToken != null ? captchaToken.strip() : "";
        if (raw.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Dokončete ověření proti spamu (Turnstile).");
        }
        if (!verifySync(raw)) {
            log.warn("turnstile failed for {}", feature);
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Ověření proti spamu se nezdařilo. Zkuste to prosím znovu.");
        }
    }

    private boolean verifySync(String token) {
        String secret = BankIntelEnvVars.get("TURNSTILE_SECRET_KEY");
        if (secret.isBlank()) {
            return false;
        }
        try {
            String body = "secret="
                    + URLEncoder.encode(secret, StandardCharsets.UTF_8)
                    + "&response="
                    + URLEncoder.encode(token, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(TURNSTILE_VERIFY)
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return false;
            }
            JsonNode data = objectMapper.readTree(response.body());
            return data.path("success").asBoolean(false);
        } catch (Exception ex) {
            log.error("turnstile siteverify failed", ex);
            return false;
        }
    }
}
