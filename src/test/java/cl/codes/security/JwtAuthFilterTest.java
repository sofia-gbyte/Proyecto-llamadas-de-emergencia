package cl.codes.security;

import cl.codes.model.User;
import cl.codes.repository.UserRepository;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtAuthFilterTest {
    private UserRepository userRepository;
    private JwtService jwtService;
    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        jwtService = new JwtService(Base64.getEncoder().encodeToString(new byte[32]), 60);
        filter = new JwtAuthFilter(jwtService, userRepository);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void mobileTokenIsRejectedOutsideMobileApi() throws ServletException, IOException {
        User operator = user("ana", "operator");
        when(userRepository.findByUsername("ana")).thenReturn(Optional.of(operator));
        MockHttpServletRequest request = request("/api/llamadas/pending", jwtService.generateMobileToken("ana"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertNull(chain.getRequest());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void mobileTokensHaveAShortTwoHourLifetime() {
        var claims = jwtService.validateAndGetClaims(jwtService.generateMobileToken("ana"));

        assertEquals(Duration.ofHours(2).toSeconds(),
                (claims.getExpiration().getTime() - claims.getIssuedAt().getTime()) / 1000);
        assertEquals(120, jwtService.getMobileExpirationMinutes());
    }

    @Test
    void enrollmentTokensExpireAfterFiveMinutesAndCannotAccessCases() throws ServletException, IOException {
        String token = jwtService.generateMobileEnrollmentToken("ana");
        var claims = jwtService.validateAndGetClaims(token);
        assertEquals(Duration.ofMinutes(5).toSeconds(),
                (claims.getExpiration().getTime() - claims.getIssuedAt().getTime()) / 1000);

        User operator = user("ana", "operator");
        when(userRepository.findByUsername("ana")).thenReturn(Optional.of(operator));
        MockHttpServletRequest request = request("/api/mobile/cases", token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertNull(chain.getRequest());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void enrollmentTokenGetsOnlyTheEnrollmentAuthority() throws ServletException, IOException {
        User operator = user("ana", "operator");
        when(userRepository.findByUsername("ana")).thenReturn(Optional.of(operator));
        MockHttpServletRequest request = request(
                "/api/auth/mobile/totp/setup", jwtService.generateMobileEnrollmentToken("ana"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertTrue(SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(authority -> "ROLE_MOBILE_ENROLLMENT".equals(authority.getAuthority())));
        assertFalse(SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(authority -> "ROLE_MOBILE_OPERATOR".equals(authority.getAuthority())));
    }

    @Test
    void mobileTokenGetsOnlyTheMobileOperatorAuthority() throws ServletException, IOException {
        User operator = user("ana", "operator");
        when(userRepository.findByUsername("ana")).thenReturn(Optional.of(operator));
        MockHttpServletRequest request = request("/api/mobile/cases", jwtService.generateMobileToken("ana"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        var authorities = SecurityContextHolder.getContext().getAuthentication().getAuthorities();
        assertTrue(authorities.stream().anyMatch(authority ->
                "ROLE_MOBILE_OPERATOR".equals(authority.getAuthority())));
        assertFalse(authorities.stream().anyMatch(authority ->
                "ROLE_OPERATOR".equals(authority.getAuthority())));
    }

    @Test
    void mobileTokenCanReachOnlyItsMobileLogoutEndpointOutsideTheMobileApi() throws ServletException, IOException {
        User operator = user("ana", "operator");
        when(userRepository.findByUsername("ana")).thenReturn(Optional.of(operator));
        MockHttpServletRequest request = request(
                "/api/auth/mobile/logout", jwtService.generateMobileToken("ana"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
        assertTrue(SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(authority -> "ROLE_MOBILE_OPERATOR".equals(authority.getAuthority())));
    }

    @Test
    void mobileTokenIsRejectedIfTheAccountRoleChanges() throws ServletException, IOException {
        User administrator = user("ana", "administrator");
        when(userRepository.findByUsername("ana")).thenReturn(Optional.of(administrator));
        MockHttpServletRequest request = request("/api/mobile/cases", jwtService.generateMobileToken("ana"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(403, response.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    private static MockHttpServletRequest request(String path, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath(path);
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    private static User user(String username, String role) {
        User user = new User();
        user.setUsername(username);
        user.setRole(role);
        user.setActive(true);
        return user;
    }
}
