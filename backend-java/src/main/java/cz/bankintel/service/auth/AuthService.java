package cz.bankintel.service.auth;

import cz.bankintel.config.BankIntelProperties;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.domain.dto.AuthDtos.MeResponse;
import cz.bankintel.repository.UserRepository;
import cz.bankintel.security.JwtService;
import cz.bankintel.security.PasswordPolicy;
import cz.bankintel.service.captcha.TurnstileService;
import cz.bankintel.service.email.VerificationEmailService;
import cz.bankintel.util.IdGenerator;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String RESEND_OK = "Pokud účet existuje a není ověřen, byl odeslán e-mail s odkazem.";
    private static final String FORGOT_OK = "Pokud účet existuje, byl odeslán e-mail s odkazem pro obnovu hesla.";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final UserMapper userMapper;
    private final VerificationEmailService verificationEmailService;
    private final SubscriberCodeService subscriberCodeService;
    private final BankIntelProperties properties;
    private final PasswordPolicy passwordPolicy;
    private final TurnstileService turnstileService;

    public LoginResult login(String email, String password) {
        UserEntity user = findByEmail(email);
        if (!verifyPassword(password, user.getPasswordHash())) {
            throw unauthorized("Neplatný e-mail nebo heslo.");
        }
        ensureEmailVerified(user);
        return tokensFor(user);
    }

    public MeResponse me(String userId) {
        return userMapper.toMeResponse(requireUser(userId));
    }

    @Transactional
    public RegisterResult register(
            String company,
            String name,
            String email,
            String phone,
            String password,
            String registrationCode,
            String captchaToken) {
        turnstileService.requireTurnstileOrBypass(captchaToken, "auth_register");
        passwordPolicy.validateOrThrow(password);
        String normalizedEmail = normalizeEmail(email);
        if (userRepository.findByEmailIgnoreCase(normalizedEmail).isPresent()) {
            throw badRequest("Email already in use");
        }

        UserEntity user = new UserEntity();
        user.setId(IdGenerator.newId());
        user.setEmail(normalizedEmail);
        user.setName(name.strip());
        user.setCompany(company.strip());
        user.setPhone(phone.strip());
        user.setRole("viewer");
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setEmailVerified(false);

        if (registrationCode == null || registrationCode.isBlank()) {
            user.setAccessTier("free");
            user.setHasPremiumAccess(false);
        } else {
            if (!subscriberCodeService.verify(registrationCode.strip())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Registrační kód není správný.");
            }
            user.setAccessTier("subscriber");
            user.setHasPremiumAccess(true);
            user.setPremiumAccessGrantedAt(Instant.now());
            user.setPremiumAccessSource("registration_code");
        }

        String rawToken = IdGenerator.newToken();
        user.setEmailVerificationTokenHash(IdGenerator.sha256Hex(rawToken));
        user.setEmailVerificationExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        userRepository.save(user);
        verificationEmailService.sendVerificationEmail(normalizedEmail, rawToken);

        return new RegisterResult(
                "pending_verification",
                "Účet byl vytvořen. Pro pokračování prosím potvrďte svůj e-mail. Odkaz jsme vám poslali do e-mailu.",
                normalizedEmail);
    }

    @Transactional
    public MessageResult verifyEmail(String rawToken) {
        if (rawToken == null || rawToken.strip().length() < 8) {
            throw badRequest("Odkaz je neplatný nebo vypršel. Požádejte o nový e-mail s ověřením.");
        }
        UserEntity user = userRepository
                .findByEmailVerificationTokenHash(IdGenerator.sha256Hex(rawToken.strip()))
                .orElseThrow(() -> badRequest("Odkaz je neplatný nebo vypršel. Požádejte o nový e-mail s ověřením."));
        if (user.isEmailVerified()) {
            return new MessageResult(true, "E-mail je již potvrzený.");
        }
        if (user.getEmailVerificationExpiresAt() == null
                || user.getEmailVerificationExpiresAt().isBefore(Instant.now())) {
            throw badRequest("Odkaz je neplatný nebo vypršel. Požádejte o nový e-mail s ověřením.");
        }
        user.setEmailVerified(true);
        user.setEmailVerifiedAt(Instant.now());
        user.setEmailVerificationTokenHash(null);
        user.setEmailVerificationExpiresAt(null);
        userRepository.save(user);
        return new MessageResult(true, "E-mail byl potvrzen. Nyní se můžete přihlásit.");
    }

    @Transactional
    public MessageResult resendVerification(String email, String captchaToken) {
        turnstileService.requireTurnstileOrBypass(captchaToken, "resend_verification");
        userRepository.findByEmailIgnoreCase(normalizeEmail(email)).ifPresent(user -> {
            if (!user.isEmailVerified()) {
                String raw = IdGenerator.newToken();
                user.setEmailVerificationTokenHash(IdGenerator.sha256Hex(raw));
                user.setEmailVerificationExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
                userRepository.save(user);
                verificationEmailService.sendVerificationEmail(user.getEmail(), raw);
            }
        });
        return new MessageResult(true, RESEND_OK);
    }

    @Transactional
    public MessageResult forgotPassword(String email, String captchaToken) {
        turnstileService.requireTurnstileOrBypass(captchaToken, "forgot_password");
        userRepository.findByEmailIgnoreCase(normalizeEmail(email)).ifPresent(user -> {
            String raw = IdGenerator.newToken();
            user.setPasswordResetTokenHash(IdGenerator.sha256Hex(raw));
            user.setPasswordResetExpiresAt(Instant.now().plus(2, ChronoUnit.HOURS));
            userRepository.save(user);
            verificationEmailService.sendPasswordResetEmail(user.getEmail(), raw);
        });
        return new MessageResult(true, FORGOT_OK);
    }

    @Transactional
    public MessageResult resetPassword(String token, String newPassword) {
        passwordPolicy.validateOrThrow(newPassword);
        UserEntity user = userRepository
                .findByPasswordResetTokenHash(IdGenerator.sha256Hex(token.strip()))
                .orElseThrow(() -> badRequest("Odkaz pro obnovu hesla je neplatný nebo vypršel. Požádejte o nový."));
        if (user.getPasswordResetExpiresAt() == null
                || user.getPasswordResetExpiresAt().isBefore(Instant.now())) {
            throw badRequest("Odkaz pro obnovu hesla je neplatný nebo vypršel. Požádejte o nový.");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordResetTokenHash(null);
        user.setPasswordResetExpiresAt(null);
        userRepository.save(user);
        return new MessageResult(true, "Heslo bylo změněno. Nyní se můžete přihlásit.");
    }

    public RefreshResult refresh(String refreshToken) {
        var claims = jwtService
                .parse(refreshToken)
                .filter(c -> "refresh".equals(c.get("type", String.class)))
                .orElseThrow(() -> unauthorized("Invalid refresh"));
        UserEntity user = requireUser(claims.getSubject());
        ensureEmailVerified(user);
        String access = jwtService.createAccessToken(user.getId(), user.getEmail(), user.getRole());
        return new RefreshResult(access, refreshToken);
    }

    private LoginResult tokensFor(UserEntity user) {
        String role = user.getRole() != null ? user.getRole() : "viewer";
        String access = jwtService.createAccessToken(user.getId(), user.getEmail(), role);
        String refresh = jwtService.createRefreshToken(user.getId());
        return new LoginResult(access, refresh, userMapper.toMeResponse(user));
    }

    private UserEntity findByEmail(String email) {
        return userRepository
                .findByEmailIgnoreCase(normalizeEmail(email))
                .orElseThrow(() -> unauthorized("Neplatný e-mail nebo heslo."));
    }

    private UserEntity requireUser(String id) {
        return userRepository
                .findById(id)
                .orElseThrow(() -> unauthorized("Unauthorized"));
    }

    private void ensureEmailVerified(UserEntity user) {
        if (!user.isEmailVerified()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Nejprve prosím potvrďte svůj e-mail. Odkaz jsme vám poslali při registraci.");
        }
    }

    private boolean verifyPassword(String plain, String hash) {
        return hash != null && hash.startsWith("$2") && passwordEncoder.matches(plain, hash);
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase();
    }

    private static ResponseStatusException unauthorized(String detail) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, detail);
    }

    private static ResponseStatusException badRequest(String detail) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
    }

    public record LoginResult(String accessToken, String refreshToken, MeResponse user) {}

    public record RegisterResult(String status, String message, String email) {}

    public record MessageResult(boolean ok, String message) {}

    public record RefreshResult(String accessToken, String refreshToken) {}
}
