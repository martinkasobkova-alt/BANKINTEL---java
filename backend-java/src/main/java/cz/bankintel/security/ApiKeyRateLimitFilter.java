package cz.bankintel.security;

import cz.bankintel.util.IdGenerator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rate limits {@code /api/connect/**} — separate from {@link AuthRateLimitFilter} because an external
 * system may sit behind a shared/NAT IP, so this is keyed by the caller's bearer token (hashed, never
 * held in memory in raw form) rather than by client IP; an unauthenticated call to this namespace
 * falls back to IP-keying so it can't be used to bypass the limit entirely.
 */
@Component
public class ApiKeyRateLimitFilter extends OncePerRequestFilter {

    private static final long MINUTE_MS = 60_000L;
    private static final int MAX_PER_MINUTE = 60;
    private static final String CONNECT_PATH_PREFIX = "/api/connect/";

    private final Map<String, List<Long>> hits = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!request.getRequestURI().startsWith(CONNECT_PATH_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }
        String key = ApiKeyAuthFilter.resolveApiKeyToken(request)
                .map(IdGenerator::sha256Hex)
                .orElseGet(() -> "ip:" + ClientIpSupport.getTrustedClientIp(request));
        if (isRateLimited(key)) {
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"detail\":\"Too many requests. Please try again later.\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isRateLimited(String key) {
        long now = System.currentTimeMillis();
        long windowStart = now - MINUTE_MS;
        List<Long> timestamps = hits.computeIfAbsent(key, ignored -> new ArrayList<>());
        synchronized (timestamps) {
            timestamps.removeIf(ts -> ts < windowStart);
            if (timestamps.size() >= MAX_PER_MINUTE) {
                return true;
            }
            timestamps.add(now);
            return false;
        }
    }
}
