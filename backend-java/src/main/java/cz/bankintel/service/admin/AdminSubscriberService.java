package cz.bankintel.service.admin;

import cz.bankintel.domain.entity.AppSettingsEntity;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.AppSettingsRepository;
import cz.bankintel.service.audit.AuditLogService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AdminSubscriberService {

    private static final String APP_CONFIG_ID = "app_config";

    private final AppSettingsRepository appSettingsRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public Map<String, Object> getCodeStatus() {
        AppSettingsEntity settings = appSettingsRepository
                .findById(APP_CONFIG_ID)
                .orElse(null);
        String hash = settings != null ? settings.getSubscriberRegistrationCodeHash() : null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("is_set", hash != null && !hash.isBlank());
        out.put("updated_at", settings != null && settings.getSubscriberCodeUpdatedAt() != null
                ? settings.getSubscriberCodeUpdatedAt().toString()
                : null);
        out.put("updated_by", settings != null ? settings.getSubscriberCodeUpdatedBy() : null);
        return out;
    }

    @Transactional
    public Map<String, Object> setRegistrationCode(String plainCode, UserEntity admin, String ip, String userAgent) {
        if (plainCode == null || plainCode.strip().length() < 6) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Registration code must be at least 6 characters.");
        }
        AppSettingsEntity settings = appSettingsRepository
                .findById(APP_CONFIG_ID)
                .orElseGet(() -> {
                    AppSettingsEntity created = new AppSettingsEntity();
                    created.setId(APP_CONFIG_ID);
                    return created;
                });
        settings.setSubscriberRegistrationCodeHash(passwordEncoder.encode(plainCode.strip()));
        settings.setSubscriberCodeUpdatedAt(Instant.now());
        settings.setSubscriberCodeUpdatedBy(admin.getId());
        settings.setUpdatedAt(Instant.now());
        appSettingsRepository.save(settings);

        auditLogService.logEvent(
                "subscriber_registration_code_updated",
                admin,
                "subscriber_code",
                "global",
                Map.of("changed", true),
                ip,
                userAgent);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("message", "Registrační kód byl změněn.");
        out.put("registration_code", plainCode.strip());
        return out;
    }
}
