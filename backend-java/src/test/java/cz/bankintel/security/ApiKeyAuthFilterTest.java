package cz.bankintel.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cz.bankintel.domain.entity.ApiKeyEntity;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.ApiKeyRepository;
import cz.bankintel.repository.UserRepository;
import cz.bankintel.util.IdGenerator;
import jakarta.servlet.FilterChain;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

class ApiKeyAuthFilterTest {

    private static final String RAW_KEY = "bik_live_test-token";

    private ApiKeyRepository apiKeyRepository;
    private UserRepository userRepository;
    private ApiKeyAuthFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        apiKeyRepository = mock(ApiKeyRepository.class);
        userRepository = mock(UserRepository.class);
        filter = new ApiKeyAuthFilter(apiKeyRepository, userRepository);
        chain = mock(FilterChain.class);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private ApiKeyEntity activeKey() {
        ApiKeyEntity key = new ApiKeyEntity();
        key.setId("key-1");
        key.setUserId("user-1");
        key.setKeyHash(IdGenerator.sha256Hex(RAW_KEY));
        key.setScopes(List.of("dashboard:write"));
        return key;
    }

    private UserEntity owningUser() {
        UserEntity user = new UserEntity();
        user.setId("user-1");
        user.setEmail("owner@example.com");
        user.setRole("subscriber");
        return user;
    }

    @Test
    void authenticatesAValidKeyOnAConnectPathAndGrantsItsScopesAsAuthorities() throws Exception {
        when(apiKeyRepository.findByKeyHash(IdGenerator.sha256Hex(RAW_KEY))).thenReturn(Optional.of(activeKey()));
        when(userRepository.findById("user-1")).thenReturn(Optional.of(owningUser()));
        when(apiKeyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MockHttpServletRequest request =
                new MockHttpServletRequest(HttpMethod.POST.name(), "/api/connect/v1/dashboards/p1/widgets");
        request.addHeader("Authorization", "Bearer " + RAW_KEY);
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isInstanceOf(JwtService.AuthenticatedUser.class);
        assertThat(((JwtService.AuthenticatedUser) auth.getPrincipal()).id()).isEqualTo("user-1");
        assertThat(auth.getAuthorities())
                .extracting(Object::toString)
                .contains("SCOPE_dashboard:write", "ROLE_SUBSCRIBER");
        verify(chain).doFilter(any(), any());
    }

    @Test
    void neverAuthenticatesOutsideTheConnectNamespaceEvenWithAValidKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(HttpMethod.GET.name(), "/api/me/dashboard/pages");
        request.addHeader("Authorization", "Bearer " + RAW_KEY);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        org.mockito.Mockito.verifyNoInteractions(apiKeyRepository);
    }

    @Test
    void rejectsARevokedKey() throws Exception {
        ApiKeyEntity revoked = activeKey();
        revoked.setRevokedAt(Instant.now());
        when(apiKeyRepository.findByKeyHash(IdGenerator.sha256Hex(RAW_KEY))).thenReturn(Optional.of(revoked));

        MockHttpServletRequest request =
                new MockHttpServletRequest(HttpMethod.GET.name(), "/api/connect/v1/widgets/tok/w1");
        request.addHeader("Authorization", "Bearer " + RAW_KEY);
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void ignoresANonApiKeyShapedBearerTokenOnAConnectPath() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest(HttpMethod.GET.name(), "/api/connect/v1/widgets/tok/w1");
        request.addHeader("Authorization", "Bearer some.jwt.token");

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        org.mockito.Mockito.verifyNoInteractions(apiKeyRepository);
    }
}
