package cz.bankintel.service.access;

import cz.bankintel.domain.entity.FeatureAccessRuleEntity;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.FeatureAccessRuleRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class FeatureAccessService {

    private static final Set<String> VALID_LEVELS = Set.of("public", "registered", "subscriber", "admin");

    private final FeatureAccessRuleRepository repository;

    public List<Map<String, Object>> listRulesPublic() {
        return repository.findAll().stream()
                .sorted((a, b) -> a.getFeatureKey().compareTo(b.getFeatureKey()))
                .map(this::toPublic)
                .toList();
    }

    public Map<String, Object> effectiveAccess(UserEntity user) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("_user_tier", userTierFlags(user));
        for (FeatureAccessRuleEntity rule : repository.findAll()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("access_level", rule.getAccessLevel());
            entry.put("allowed", canAccess(user, rule.getAccessLevel()));
            out.put(rule.getFeatureKey(), entry);
        }
        return out;
    }

    public void requireFeature(UserEntity user, String featureKey) {
        FeatureAccessRuleEntity rule = repository
                .findById(featureKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Funkce není nakonfigurovaná."));
        if (!canAccess(user, rule.getAccessLevel())) {
            throw forbiddenForLevel(rule.getAccessLevel(), featureKey);
        }
    }

    public boolean canAccessFeature(UserEntity user, String featureKey) {
        return repository.findById(featureKey).map(r -> canAccess(user, r.getAccessLevel())).orElse(false);
    }

    @Transactional
    public Map<String, Object> updateAccessLevel(String featureKey, String accessLevel) {
        if (!VALID_LEVELS.contains(accessLevel)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid access level");
        }
        FeatureAccessRuleEntity rule = repository
                .findById(featureKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown feature key"));
        rule.setAccessLevel(accessLevel);
        rule.setUpdatedAt(java.time.Instant.now());
        repository.save(rule);
        return toPublic(rule);
    }

    private boolean canAccess(UserEntity user, String level) {
        if (!VALID_LEVELS.contains(level)) {
            return false;
        }
        return switch (level) {
            case "public" -> true;
            case "registered" -> user != null;
            case "subscriber" -> isSubscriber(user);
            case "admin" -> user != null && "admin".equalsIgnoreCase(user.getRole());
            default -> false;
        };
    }

    public static boolean isSubscriber(UserEntity user) {
        if (user == null) {
            return false;
        }
        if ("admin".equalsIgnoreCase(user.getRole())) {
            return true;
        }
        return user.isHasPremiumAccess() || "subscriber".equalsIgnoreCase(user.getAccessTier());
    }

    public static Map<String, Boolean> userTierFlags(UserEntity user) {
        Map<String, Boolean> flags = new LinkedHashMap<>();
        if (user == null) {
            flags.put("public", true);
            flags.put("registered", false);
            flags.put("subscriber", false);
            flags.put("admin", false);
            return flags;
        }
        if ("admin".equalsIgnoreCase(user.getRole())) {
            flags.put("public", true);
            flags.put("registered", true);
            flags.put("subscriber", true);
            flags.put("admin", true);
            return flags;
        }
        if (isSubscriber(user)) {
            flags.put("public", true);
            flags.put("registered", true);
            flags.put("subscriber", true);
            flags.put("admin", false);
            return flags;
        }
        flags.put("public", true);
        flags.put("registered", true);
        flags.put("subscriber", false);
        flags.put("admin", false);
        return flags;
    }

    private Map<String, Object> toPublic(FeatureAccessRuleEntity rule) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("feature_key", rule.getFeatureKey());
        map.put("label", rule.getLabel());
        map.put("description", rule.getDescription());
        map.put("access_level", rule.getAccessLevel());
        return map;
    }

    private ResponseStatusException forbiddenForLevel(String level, String featureKey) {
        return switch (level) {
            case "registered" -> new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Tato funkce je dostupná po přihlášení.");
            case "subscriber" -> new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "export_data".equals(featureKey)
                            ? "Export dat je dostupný pouze pro předplatitele časopisu Bankovnictví."
                            : "Tato funkce je dostupná pro předplatitele časopisu Bankovnictví.");
            case "admin" -> new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Tuto funkci mohou používat pouze správci.");
            default -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Přístup k této funkci odepřen.");
        };
    }
}
