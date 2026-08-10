package cz.bankintel.controller;

import cz.bankintel.domain.dto.AdminDtos.FeatureAccessLevelUpdate;
import cz.bankintel.security.AdminAccess;
import cz.bankintel.security.CurrentUser;
import cz.bankintel.service.access.FeatureAccessService;
import cz.bankintel.service.audit.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/feature-access")
@RequiredArgsConstructor
public class FeatureAccessController {

    private final FeatureAccessService featureAccessService;
    private final CurrentUser currentUser;
    private final AdminAccess adminAccess;
    private final AuditLogService auditLogService;

    @GetMapping({"", "/"})
    public List<Map<String, Object>> listRules() {
        return featureAccessService.listRulesPublic();
    }

    @GetMapping("/effective")
    public Map<String, Object> effective() {
        return featureAccessService.effectiveAccess(currentUser.optionalUserEntity());
    }

    @PutMapping("/{featureKey}")
    public Map<String, Object> putFeatureAccess(
            @PathVariable String featureKey,
            @Valid @RequestBody FeatureAccessLevelUpdate body,
            HttpServletRequest httpRequest) {
        var admin = adminAccess.requireAdmin();
        var existing = featureAccessService.listRulesPublic().stream()
                .filter(r -> featureKey.equals(r.get("feature_key")))
                .findFirst()
                .orElse(null);
        String oldLevel = existing != null ? String.valueOf(existing.get("access_level")) : null;
        Map<String, Object> updated = featureAccessService.updateAccessLevel(featureKey, body.accessLevel());
        auditLogService.logEvent(
                "feature_access_updated",
                admin,
                "feature_access_rule",
                featureKey,
                Map.of(
                        "feature_key", featureKey,
                        "old_access_level", oldLevel,
                        "new_access_level", body.accessLevel()),
                httpRequest.getRemoteAddr(),
                httpRequest.getHeader("User-Agent"));
        return updated;
    }
}
