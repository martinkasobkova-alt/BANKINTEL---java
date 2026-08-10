package cz.bankintel.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import cz.bankintel.config.BankIntelProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class CsrfFilterTest {

    private CsrfFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new CsrfFilter(mock(BankIntelProperties.class));
        chain = mock(FilterChain.class);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getRequestsSkipCsrfCheck() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(HttpMethod.GET.name(), "/api/stocks/search");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertEquals(200, response.getStatus());
    }

    @Test
    void exemptAuthPathsSkipCsrfCheck() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(HttpMethod.POST.name(), "/api/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertEquals(200, response.getStatus());
    }

    /**
     * Klíčový regresní test pro tuto opravu: stará implementace kontrolovala jen HOLOU
     * PŘÍTOMNOST access/refresh cookie (bez ohledu na platnost). Prošlý/neplatný token tak
     * shazoval i čistě veřejné požadavky (stock search, preview z našeptávače) hláškou "CSRF
     * token missing or invalid", i když JwtAuthFilter request vůbec nepřihlásil (viz.
     * SecurityContextHolder je prázdný, protože JwtAuthFilter běží před tímto filtrem).
     */
    @Test
    void unauthenticatedRequestWithStaleCookiePassesThroughWithoutCsrfHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(HttpMethod.POST.name(), "/api/stocks/search");
        request.setCookies(new Cookie(JwtService.ACCESS_COOKIE, "expired-or-invalid-jwt"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
    }

    @Test
    void authenticatedRequestMissingCsrfHeaderIsBlocked() throws Exception {
        setAuthenticated();
        MockHttpServletRequest request = new MockHttpServletRequest(HttpMethod.POST.name(), "/api/stocks/search");
        request.setCookies(new Cookie(JwtService.ACCESS_COOKIE, "valid-jwt"), new Cookie(JwtService.CSRF_COOKIE, "abc123"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertEquals("{\"detail\":\"CSRF token missing or invalid\"}", response.getContentAsString());
    }

    @Test
    void authenticatedRequestMismatchedCsrfHeaderIsBlocked() throws Exception {
        setAuthenticated();
        MockHttpServletRequest request = new MockHttpServletRequest(HttpMethod.POST.name(), "/api/stocks/search");
        request.setCookies(new Cookie(JwtService.ACCESS_COOKIE, "valid-jwt"), new Cookie(JwtService.CSRF_COOKIE, "abc123"));
        request.addHeader("X-CSRF-Token", "wrong-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
    }

    @Test
    void authenticatedRequestWithMatchingCsrfHeaderPasses() throws Exception {
        setAuthenticated();
        MockHttpServletRequest request = new MockHttpServletRequest(HttpMethod.POST.name(), "/api/stocks/search");
        request.setCookies(new Cookie(JwtService.ACCESS_COOKIE, "valid-jwt"), new Cookie(JwtService.CSRF_COOKIE, "abc123"));
        request.addHeader("X-CSRF-Token", "abc123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
    }

    private static void setAuthenticated() {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("admin@bankintel.local", null, java.util.List.of()));
    }
}
