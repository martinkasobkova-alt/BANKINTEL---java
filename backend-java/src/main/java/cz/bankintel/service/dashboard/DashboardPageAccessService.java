package cz.bankintel.service.dashboard;

import cz.bankintel.domain.entity.DashboardPageEntity;
import cz.bankintel.domain.entity.UserEntity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class DashboardPageAccessService {

    public enum ViewRole {
        OWNER,
        VIEWER,
        DENIED
    }

    public String normalizeAccessMode(String raw) {
        String v = raw != null ? raw.strip().toLowerCase() : "";
        if ("invite_only".equals(v) || "invite".equals(v) || "restricted".equals(v)) {
            return "invite_only";
        }
        if ("public".equals(v)) {
            return "public";
        }
        return "owner_only";
    }

    public ViewRole resolvePageViewRole(DashboardPageEntity page, UserEntity user, String shareToken) {
        if (page == null) {
            return ViewRole.DENIED;
        }
        String ownerId = page.getUserId() != null ? page.getUserId().strip() : "";
        String viewerId = user != null && user.getId() != null ? user.getId().strip() : "";
        if (!ownerId.isEmpty() && ownerId.equals(viewerId)) {
            return ViewRole.OWNER;
        }

        String mode = normalizeAccessMode(page.getAccessMode());
        if ("owner_only".equals(mode)) {
            return ViewRole.DENIED;
        }
        if ("public".equals(mode)) {
            return ViewRole.VIEWER;
        }

        if (!viewerId.isEmpty() && allowedUserIds(page).contains(viewerId)) {
            return ViewRole.VIEWER;
        }
        String token = shareToken != null ? shareToken.strip() : "";
        String pageToken = page.getShareToken() != null ? page.getShareToken().strip() : "";
        if (!token.isEmpty() && tokensMatch(token, pageToken) && page.isShareEnabled()) {
            return ViewRole.VIEWER;
        }
        return ViewRole.DENIED;
    }

    public ViewRole resolvePageViewRole(DashboardPageEntity page, UserEntity user) {
        return resolvePageViewRole(page, user, null);
    }

    /** Constant-time comparison — a share token is a bearer secret, not just an identifier. */
    private static boolean tokensMatch(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static Set<String> allowedUserIds(DashboardPageEntity page) {
        Set<String> allowed = new HashSet<>();
        List<String> raw = page.getAllowedUserIds();
        if (raw == null) {
            return allowed;
        }
        for (String id : raw) {
            if (id != null && !id.strip().isEmpty()) {
                allowed.add(id.strip());
            }
        }
        return allowed;
    }
}
