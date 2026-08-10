package cz.bankintel.service.auth;

import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.domain.dto.AuthDtos.MeResponse;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {

    public MeResponse toMeResponse(UserEntity user) {
        String tier = user.getAccessTier() != null ? user.getAccessTier() : "free";
        boolean subscriber = "subscriber".equalsIgnoreCase(tier) || user.isHasPremiumAccess();
        return new MeResponse(
                user.getId(),
                user.getCompany(),
                user.getEmail(),
                user.getName(),
                user.getPhone(),
                normalizeRole(user.getRole()),
                tier,
                user.isHasPremiumAccess(),
                user.getPremiumAccessGrantedAt() != null ? user.getPremiumAccessGrantedAt().toString() : null,
                user.getPremiumAccessSource(),
                subscriber,
                user.isOpenPersonalDashboardOnLogin(),
                user.getDefaultDashboardPageId(),
                user.isEmailVerified());
    }

    private static String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "viewer";
        }
        return role.trim().toLowerCase();
    }
}
