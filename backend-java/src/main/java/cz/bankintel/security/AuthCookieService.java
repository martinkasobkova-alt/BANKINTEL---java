package cz.bankintel.security;

import cz.bankintel.config.BankIntelProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class AuthCookieService {

    private final BankIntelProperties properties;

    public AuthCookieService(BankIntelProperties properties) {
        this.properties = properties;
    }

    public void setAuthCookies(HttpServletResponse response, String access, String refresh) {
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookie(JwtService.ACCESS_COOKIE, access, maxAgeAccess(), true).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookie(JwtService.REFRESH_COOKIE, refresh, maxAgeRefresh(), true).toString());
    }

    public void setCsrfCookie(HttpServletResponse response, String csrf) {
        response.addHeader(
                HttpHeaders.SET_COOKIE,
                buildCookie(JwtService.CSRF_COOKIE, csrf, maxAgeAccess(), false).toString());
    }

    public void clearAuthCookies(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookie(JwtService.ACCESS_COOKIE, "", 0, true).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookie(JwtService.REFRESH_COOKIE, "", 0, true).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookie(JwtService.CSRF_COOKIE, "", 0, false).toString());
    }

    private ResponseCookie buildCookie(String name, String value, long maxAgeSeconds, boolean httpOnly) {
        var builder = ResponseCookie.from(name, value)
                .path("/")
                .httpOnly(httpOnly)
                .secure(properties.cookie().secure())
                .sameSite(properties.cookie().sameSite())
                .maxAge(maxAgeSeconds);
        if (properties.cookie().domain() != null && !properties.cookie().domain().isBlank()) {
            builder.domain(properties.cookie().domain());
        }
        return builder.build();
    }

    private long maxAgeAccess() {
        return properties.jwt().accessTtlMinutes() * 60L;
    }

    private long maxAgeRefresh() {
        return properties.jwt().refreshTtlDays() * 24L * 3600L;
    }
}
