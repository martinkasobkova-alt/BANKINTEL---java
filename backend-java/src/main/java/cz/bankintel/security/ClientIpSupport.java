package cz.bankintel.security;

import cz.bankintel.util.BankIntelEnvVars;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Resolves client IP behind reverse proxy (X-Forwarded-For / X-Real-IP). */
public final class ClientIpSupport {

    private ClientIpSupport() {}

    public static String getTrustedClientIp(HttpServletRequest request) {
        String directRaw = request.getRemoteAddr();
        String direct = normalizeIp(directRaw);
        if (direct.isBlank()) {
            direct = "127.0.0.1";
        }

        Set<String> trusted = trustedProxySet();
        if (!trusted.isEmpty() && trusted.contains(direct)) {
            String fromXff = firstFromXForwarded(request.getHeader("X-Forwarded-For"));
            if (!fromXff.isBlank()) {
                return fromXff;
            }
            String xri = normalizeIp(request.getHeader("X-Real-IP"));
            if (!xri.isBlank()) {
                return xri;
            }
            return direct;
        }

        if (BankIntelEnvVars.isTruthy("TRUST_RENDER_PROXY_HEADERS")) {
            String fromXff = firstFromXForwarded(request.getHeader("X-Forwarded-For"));
            if (!fromXff.isBlank()) {
                return fromXff;
            }
            String xri = normalizeIp(request.getHeader("X-Real-IP"));
            if (!xri.isBlank()) {
                return xri;
            }
        }

        return direct;
    }

    private static Set<String> trustedProxySet() {
        String raw = BankIntelEnvVars.get("TRUSTED_PROXY_IPS");
        if (raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .map(ClientIpSupport::normalizeIp)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());
    }

    static String firstFromXForwarded(String xff) {
        if (xff == null || xff.isBlank()) {
            return "";
        }
        for (String part : xff.split(",")) {
            String candidate = normalizeIp(part.trim());
            if (!candidate.isBlank()) {
                return candidate;
            }
        }
        return "";
    }

    static String normalizeIp(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.strip();
        if (trimmed.startsWith("[") && trimmed.contains("]")) {
            trimmed = trimmed.substring(1, trimmed.indexOf(']')).strip();
        }
        if (trimmed.contains(":") && !trimmed.startsWith("[") && trimmed.chars().filter(c -> c == ':').count() == 1) {
            trimmed = trimmed.substring(0, trimmed.lastIndexOf(':'));
        }
        try {
            return InetAddress.getByName(trimmed).getHostAddress();
        } catch (Exception ex) {
            return "";
        }
    }
}
