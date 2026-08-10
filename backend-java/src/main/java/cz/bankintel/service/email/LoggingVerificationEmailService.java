package cz.bankintel.service.email;
import cz.bankintel.util.BankIntelEnvVars;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.config.BankIntelProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LoggingVerificationEmailService implements VerificationEmailService {

    private static final Logger log = LoggerFactory.getLogger(LoggingVerificationEmailService.class);
    private static final URI RESEND_API = URI.create("https://api.resend.com/emails");

    private final BankIntelProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    @Override
    public void sendVerificationEmail(String email, String rawToken) {
        String verifyUrl = frontendBase() + "/verify-email?token=" + rawToken;
        String html =
                """
                <p>Dobrý den,</p>
                <p>pro dokončení registrace prosím potvrďte svou e-mailovou adresu kliknutím na odkaz níže (platnost 24 hodin).</p>
                <p><a href="%s">Potvrdit e-mail</a></p>
                <p>Pokud jste o účet nežádali, tento e-mail ignorujte.</p>
                """
                        .formatted(verifyUrl);
        if (!sendViaResend(email, "Potvrzení e-mailu — Bankovnictví", html)) {
            logDevToken("Verification", email, rawToken);
        }
    }

    @Override
    public void sendPasswordResetEmail(String email, String rawToken) {
        String resetUrl = frontendBase() + "/reset-password?token=" + rawToken;
        String html =
                """
                <p>Dobrý den,</p>
                <p>požádali jste o obnovení hesla. Klikněte na odkaz níže a nastavte nové heslo (platnost 2 hodiny).</p>
                <p><a href="%s">Nastavit nové heslo</a></p>
                <p>Pokud jste o obnovu nežádali, tento e-mail ignorujte.</p>
                """
                        .formatted(resetUrl);
        if (!sendViaResend(email, "Obnovení hesla — Bankovnictví", html)) {
            logDevToken("Password reset", email, rawToken);
        }
    }

    private boolean sendViaResend(String toEmail, String subject, String html) {
        if (!resendConfigured()) {
            log.warn("Resend: chybí RESEND_API_KEY, SENDER_EMAIL nebo FRONTEND_URL — e-mail se neodešle.");
            return false;
        }
        try {
            Map<String, Object> body = Map.of(
                    "from", env("SENDER_EMAIL"),
                    "to", new String[] {toEmail},
                    "subject", subject,
                    "html", html);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(RESEND_API)
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + env("RESEND_API_KEY"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                log.error("Resend HTTP {}: {}", response.statusCode(), response.body());
                return false;
            }
            return true;
        } catch (Exception ex) {
            log.error("Resend: odeslání e-mailu selhalo", ex);
            return false;
        }
    }

    private void logDevToken(String kind, String email, String rawToken) {
        if (properties.dev().logVerificationTokens()) {
            log.info("[dev] {} email to {} — token: {}", kind, email, rawToken);
        } else {
            log.info("{} email queued for {}", kind, email);
        }
    }

    private static boolean resendConfigured() {
        return !env("RESEND_API_KEY").isBlank()
                && !env("SENDER_EMAIL").isBlank()
                && !env("FRONTEND_URL").isBlank();
    }

    private static String frontendBase() {
        return env("FRONTEND_URL").replaceAll("/+$", "");
    }

    private static String env(String name) {
        String value = BankIntelEnvVars.get(name);
        return value != null ? value.trim() : "";
    }
}
