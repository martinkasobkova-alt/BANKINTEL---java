package cz.bankintel.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import cz.bankintel.util.BankIntelEnvVars;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Simple in-memory rate limits for auth endpoints, keyed by trusted client IP. */
@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final long MINUTE_MS = 60_000L;

    private final Map<String, List<Long>> hits = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        if (localSearchV2AuditBypass(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        Integer limit = limitForPath(request.getRequestURI());
        if (limit == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String key = request.getRequestURI() + "|" + ClientIpSupport.getTrustedClientIp(request);
        if (isRateLimited(key, limit)) {
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"detail\":\"Too many requests. Please try again later.\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private static Integer limitForPath(String path) {
        return switch (path) {
            case "/api/auth/login" -> 10;
            case "/api/auth/register",
                    "/api/auth/resend-verification",
                    "/api/auth/forgot-password",
                    "/api/auth/reset-password" -> 15;
            case "/api/auth/refresh" -> 30;
            case "/api/catalog/deep-search",
                    "/api/catalog/search-v2",
                    "/api/catalog/search-v2/evaluate",
                    "/api/catalog/deep-search/followup",
                    "/api/catalog/deep-search/results-chat" -> 8;
            case "/api/me/change-password" -> 5;
            default -> null;
        };
    }

    private static boolean localSearchV2AuditBypass(HttpServletRequest request) {
        if (!BankIntelEnvVars.isTruthy("SEARCH_V2_LOCAL_RATE_LIMIT_BYPASS")) {
            return false;
        }
        String path = request.getRequestURI();
        boolean searchV2Path = path.equals("/api/catalog/search-v2")
                || path.equals("/api/catalog/deep-search")
                || path.equals("/api/catalog/search-v2/evaluate");
        if (!searchV2Path) {
            return false;
        }
        String ip = ClientIpSupport.getTrustedClientIp(request);
        return "127.0.0.1".equals(ip) || "::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip) || "localhost".equals(ip);
    }

    private boolean isRateLimited(String key, int maxPerMinute) {
        long now = System.currentTimeMillis();
        long windowStart = now - MINUTE_MS;
        List<Long> timestamps = hits.computeIfAbsent(key, ignored -> new ArrayList<>());
        synchronized (timestamps) {
            timestamps.removeIf(ts -> ts < windowStart);
            if (timestamps.size() >= maxPerMinute) {
                return true;
            }
            timestamps.add(now);
            return false;
        }
    }
}
