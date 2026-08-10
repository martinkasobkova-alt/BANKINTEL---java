package cz.bankintel.service.auth;

import cz.bankintel.config.BankIntelProperties;
import cz.bankintel.repository.AppSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SubscriberCodeService {

    private final AppSettingsRepository appSettingsRepository;
    private final PasswordEncoder passwordEncoder;
    private final BankIntelProperties properties;

    public boolean verify(String plainCode) {
        if (plainCode == null || plainCode.isBlank()) {
            return false;
        }
        var envCode = properties.subscriberRegistrationCode();
        if (envCode != null && !envCode.isBlank() && envCode.equals(plainCode)) {
            return true;
        }
        return appSettingsRepository
                .findById("app_config")
                .map(s -> s.getSubscriberRegistrationCodeHash())
                .filter(hash -> hash != null && !hash.isBlank())
                .map(hash -> passwordEncoder.matches(plainCode, hash))
                .orElse(false);
    }
}
