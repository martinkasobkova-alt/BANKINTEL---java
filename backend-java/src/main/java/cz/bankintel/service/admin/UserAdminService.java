package cz.bankintel.service.admin;

import cz.bankintel.domain.dto.AdminDtos.AdminPatchUserRequest;
import cz.bankintel.domain.dto.AdminDtos.CreateUserRequest;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.UserRepository;
import cz.bankintel.service.audit.AuditLogService;
import cz.bankintel.util.IdGenerator;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class UserAdminService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listUsers() {
        return userRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toListItem)
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getUser(String userId) {
        UserEntity user = userRepository
                .findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        return toDetail(user);
    }

    @Transactional
    public Map<String, Object> createUser(CreateUserRequest request) {
        String email = request.email().strip().toLowerCase();
        if (userRepository.findByEmailIgnoreCase(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email already in use");
        }
        String role = request.role() != null && !request.role().isBlank() ? request.role().strip() : "viewer";
        boolean isAdmin = "admin".equalsIgnoreCase(role);
        Instant now = Instant.now();

        UserEntity user = new UserEntity();
        user.setId(IdGenerator.newId());
        user.setEmail(email);
        user.setName(request.name().strip());
        user.setRole(role);
        user.setCompany(blankToNull(request.company()));
        user.setPhone(blankToNull(request.phone()));
        user.setAccessTier(isAdmin ? "admin" : "free");
        user.setHasPremiumAccess(isAdmin);
        user.setPremiumAccessGrantedAt(isAdmin ? now : null);
        user.setPremiumAccessSource(isAdmin ? "admin" : null);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setEmailVerified(true);
        userRepository.save(user);
        return toListItem(user);
    }

    @Transactional
    public Map<String, Object> patchUser(
            String userId, AdminPatchUserRequest request, UserEntity admin, String ip, String userAgent) {
        if (request.hasPremiumAccess() && !"subscriber".equals(request.accessTier())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Premium access requires access_tier subscriber");
        }
        if (!request.hasPremiumAccess() && !"free".equals(request.accessTier())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Removing premium requires access_tier free");
        }
        UserEntity user = userRepository
                .findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if ("admin".equalsIgnoreCase(user.getRole())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Premium flags for admin accounts are fixed; use role management if needed.");
        }

        String oldTier = user.getAccessTier() != null ? user.getAccessTier() : "free";
        boolean oldPremium = user.isHasPremiumAccess();

        user.setAccessTier(request.accessTier());
        user.setHasPremiumAccess(request.hasPremiumAccess());
        if (request.hasPremiumAccess()) {
            if (user.getPremiumAccessGrantedAt() == null) {
                user.setPremiumAccessGrantedAt(Instant.now());
            }
            user.setPremiumAccessSource("admin");
        } else {
            user.setPremiumAccessSource(null);
            user.setPremiumAccessGrantedAt(null);
        }
        userRepository.save(user);

        auditLogService.logEvent(
                "user_premium_access_updated",
                admin,
                "user",
                userId,
                Map.of(
                        "target_user_id", userId,
                        "old_access_tier", oldTier,
                        "new_access_tier", user.getAccessTier() != null ? user.getAccessTier() : "free",
                        "old_has_premium_access", oldPremium,
                        "new_has_premium_access", user.isHasPremiumAccess(),
                        "premium_access_source", user.getPremiumAccessSource() != null
                                ? user.getPremiumAccessSource()
                                : ""),
                ip,
                userAgent);
        return toDetail(user);
    }

    @Transactional
    public Map<String, Object> deleteUser(String userId, UserEntity admin, String ip, String userAgent) {
        if (admin.getId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You cannot delete yourself");
        }
        UserEntity target = userRepository
                .findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        String email = target.getEmail();
        userRepository.delete(target);
        auditLogService.logEvent(
                "user_deleted",
                admin,
                "user",
                userId,
                Map.of("deleted_user_id", userId, "deleted_user_email", truncate(email, 320)),
                ip,
                userAgent);
        return Map.of("deleted", 1);
    }

    private Map<String, Object> toListItem(UserEntity user) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", user.getId());
        out.put("email", user.getEmail());
        out.put("name", user.getName());
        out.put("role", user.getRole());
        out.put("company", user.getCompany());
        out.put("phone", user.getPhone());
        out.put("access_tier", user.getAccessTier() != null ? user.getAccessTier() : "free");
        out.put("has_premium_access", user.isHasPremiumAccess());
        out.put("premium_access_source", user.getPremiumAccessSource());
        out.put("created_at", user.getCreatedAt() != null ? user.getCreatedAt().toString() : null);
        return out;
    }

    private Map<String, Object> toDetail(UserEntity user) {
        Map<String, Object> out = toListItem(user);
        out.put(
                "premium_access_granted_at",
                user.getPremiumAccessGrantedAt() != null ? user.getPremiumAccessGrantedAt().toString() : null);
        return out;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
