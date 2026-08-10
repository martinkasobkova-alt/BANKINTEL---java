package cz.bankintel.security;

import cz.bankintel.config.BankIntelProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class CsrfFilter extends OncePerRequestFilter {

    private static final Set<String> EXEMPT_PATHS = Set.of(
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/refresh");

    private static final String CSRF_HEADER = "X-CSRF-Token";

    private final BankIntelProperties properties;

    public CsrfFilter(BankIntelProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (shouldSkip(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        // JwtAuthFilter běží před tímto filtrem (viz SecurityConfig), takže SecurityContext už
        // odráží, jestli je request opravdu přihlášený. Dřív se tu kontrolovala jen HOLÁ
        // PŘÍTOMNOST access/refresh cookie bez ohledu na platnost - prošlý/neplatný token tak
        // tiše shazoval i čistě veřejné požadavky (stock search, preview ověření z
        // našeptávače…) hláškou "CSRF token missing or invalid".
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            filterChain.doFilter(request, response);
            return;
        }
        String cookieToken = JwtAuthFilter.readCookie(request, JwtService.CSRF_COOKIE).orElse("");
        String headerToken = request.getHeader(CSRF_HEADER);
        if (cookieToken.isBlank() || headerToken == null || !cookieToken.equals(headerToken)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"detail\":\"CSRF token missing or invalid\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean shouldSkip(HttpServletRequest request) {
        if (HttpMethod.GET.matches(request.getMethod())
                || HttpMethod.HEAD.matches(request.getMethod())
                || HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        return EXEMPT_PATHS.contains(request.getRequestURI());
    }
}
