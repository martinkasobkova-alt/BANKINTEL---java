package cz.bankintel.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthRateLimitFilterTest {

    private AuthRateLimitFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new AuthRateLimitFilter();
        chain = mock(FilterChain.class);
    }

    @Test
    void loginAllowsUnderLimit() throws Exception {
        MockHttpServletRequest request = post("/api/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        for (int i = 0; i < 10; i++) {
            filter.doFilter(request, response, chain);
            assertEquals(200, response.getStatus());
            response = new MockHttpServletResponse();
        }
    }

    @Test
    void loginBlocksEleventhRequestPerMinute() throws Exception {
        MockHttpServletRequest request = post("/api/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        for (int i = 0; i < 10; i++) {
            filter.doFilter(request, response, chain);
            response = new MockHttpServletResponse();
        }
        response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertEquals(429, response.getStatus());
        assertTrue(response.getContentAsString().contains("Too many requests"));
    }

    @Test
    void deepSearchLimitedToEightPerMinute() throws Exception {
        MockHttpServletRequest request = post("/api/catalog/deep-search");
        MockHttpServletResponse response = new MockHttpServletResponse();
        for (int i = 0; i < 8; i++) {
            filter.doFilter(request, response, chain);
            response = new MockHttpServletResponse();
        }
        response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertEquals(429, response.getStatus());
    }

    @Test
    void changePasswordLimitedToFivePerMinute() throws Exception {
        MockHttpServletRequest request = post("/api/me/change-password");
        MockHttpServletResponse response = new MockHttpServletResponse();
        for (int i = 0; i < 5; i++) {
            filter.doFilter(request, response, chain);
            response = new MockHttpServletResponse();
        }
        response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertEquals(429, response.getStatus());
    }

    @Test
    void getRequestsAreNotLimited() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(HttpMethod.GET.name(), "/api/auth/login");
        request.setRemoteAddr("10.0.0.5");
        MockHttpServletResponse response = new MockHttpServletResponse();
        for (int i = 0; i < 20; i++) {
            filter.doFilter(request, response, chain);
            assertEquals(200, response.getStatus());
        }
    }

    @Test
    void unrelatedPostPathsPassThrough() throws Exception {
        MockHttpServletRequest request = post("/api/catalog/preview");
        MockHttpServletResponse response = new MockHttpServletResponse();
        for (int i = 0; i < 20; i++) {
            filter.doFilter(request, response, chain);
            assertEquals(200, response.getStatus());
        }
    }

    private static MockHttpServletRequest post(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(HttpMethod.POST.name(), path);
        request.setRemoteAddr("192.168.1.10");
        return request;
    }
}
