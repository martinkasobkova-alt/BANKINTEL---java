package cz.bankintel.util;

public final class RoleUtils {

    private RoleUtils() {}

    public static String normalizeRole(String role) {
        String r = role == null ? "" : role.trim().toLowerCase();
        if ("admin".equals(r) || "administrator".equals(r)) {
            return "admin";
        }
        if ("editor".equals(r) || "editors".equals(r) || "redaktor".equals(r) || "redactor".equals(r)) {
            return "editor";
        }
        return "viewer";
    }

    public static boolean isAdminRole(String role) {
        return "admin".equals(normalizeRole(role));
    }

    public static boolean isContentManager(String role) {
        String normalized = normalizeRole(role);
        return "admin".equals(normalized) || "editor".equals(normalized);
    }
}
