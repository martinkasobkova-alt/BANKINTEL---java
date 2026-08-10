package cz.bankintel.security;

import cz.bankintel.domain.entity.UserEntity;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class RoleGuard {

    public UserEntity requireAdmin(UserEntity user) {
        if (!isAdminRole(user.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Insufficient permissions");
        }
        return user;
    }

    public UserEntity requireEditor(UserEntity user) {
        if (!isContentManager(user.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Insufficient permissions");
        }
        return user;
    }

    public static boolean isAdminRole(String role) {
        return "admin".equalsIgnoreCase(normalizeRole(role));
    }

    public static boolean isContentManager(String role) {
        String normalized = normalizeRole(role);
        return "admin".equals(normalized) || "editor".equals(normalized);
    }

    private static String normalizeRole(String role) {
        if (role == null) {
            return "viewer";
        }
        String r = role.trim().toLowerCase();
        if ("administrator".equals(r)) {
            return "admin";
        }
        if ("editors".equals(r) || "redaktor".equals(r) || "redactor".equals(r)) {
            return "editor";
        }
        return r;
    }
}
