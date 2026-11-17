package cl.codes.controller;

import cl.codes.controller.dto.LoginRequest;
import cl.codes.controller.dto.MobileLoginRequest;
import cl.codes.model.User;
import cl.codes.repository.UserRepository;
import cl.codes.security.AltchaService;
import cl.codes.security.AuthRateLimitService;
import cl.codes.security.JwtService;
import cl.codes.security.LoginAttemptService;
import cl.codes.service.AccountRegistrationService;
import cl.codes.service.AuditLogService;
import cl.codes.service.EmailVerificationService;
import cl.codes.service.PasswordResetService;
import cl.codes.service.SecurityService;
import cl.codes.service.MobileMfaService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthControllerMobileLoginTest {
    @Test
    void rejectsTestOperatorAccountsFromMobileLogin() {
        AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
        JwtService jwtService = mock(JwtService.class);
        LoginAttemptService loginAttemptService = mock(LoginAttemptService.class);
        AuthRateLimitService authRateLimitService = mock(AuthRateLimitService.class);
        UserRepository userRepository = mock(UserRepository.class);
        SecurityService securityService = mock(SecurityService.class);
        AltchaService altchaService = mock(AltchaService.class);
        PasswordResetService passwordResetService = mock(PasswordResetService.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        AccountRegistrationService accountRegistrationService = mock(AccountRegistrationService.class);
        EmailVerificationService emailVerificationService = mock(EmailVerificationService.class);
        MobileMfaService mobileMfaService = mock(MobileMfaService.class);
        AuthController controller = new AuthController(
                authenticationManager, jwtService, loginAttemptService, authRateLimitService,
                userRepository, securityService, altchaService, passwordResetService,
                auditLogService, accountRegistrationService, emailVerificationService, mobileMfaService);

        User testOperator = new User();
        testOperator.setUsername("operador-prueba");
        testOperator.setTestAccount(true);
        when(authRateLimitService.allowLogin("192.168.1.25")).thenReturn(true);
        when(altchaService.verify("captcha", "login")).thenReturn(true);
        when(userRepository.findByUsername("operador-prueba")).thenReturn(Optional.of(testOperator));
        when(authenticationManager.authenticate(any())).thenReturn(
                new UsernamePasswordAuthenticationToken("operador-prueba", null,
                        List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("192.168.1.25");

        var response = controller.mobileLogin(
                new MobileLoginRequest("operador-prueba", "password", "captcha", null), request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(jwtService, never()).generateMobileToken(any());
        verify(auditLogService).recordEvent("operador-prueba", "mobile-login", "session",
                null, "192.168.1.25", "rejected-test-account");
    }

    @Test
    void mobileLogoutInvalidatesTheUserSessionsAndAuditsTheEvent() {
        AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
        JwtService jwtService = mock(JwtService.class);
        LoginAttemptService loginAttemptService = mock(LoginAttemptService.class);
        AuthRateLimitService authRateLimitService = mock(AuthRateLimitService.class);
        UserRepository userRepository = mock(UserRepository.class);
        SecurityService securityService = mock(SecurityService.class);
        AltchaService altchaService = mock(AltchaService.class);
        PasswordResetService passwordResetService = mock(PasswordResetService.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        AccountRegistrationService accountRegistrationService = mock(AccountRegistrationService.class);
        EmailVerificationService emailVerificationService = mock(EmailVerificationService.class);
        MobileMfaService mobileMfaService = mock(MobileMfaService.class);
        AuthController controller = new AuthController(
                authenticationManager, jwtService, loginAttemptService, authRateLimitService,
                userRepository, securityService, altchaService, passwordResetService,
                auditLogService, accountRegistrationService, emailVerificationService, mobileMfaService);
        User user = new User();
        user.setUsername("ana");
        when(userRepository.findByUsername("ana")).thenReturn(Optional.of(user));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("192.168.1.25");
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "ana", null, List.of(new SimpleGrantedAuthority("ROLE_MOBILE_OPERATOR")));

        var response = controller.mobileLogout(auth, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(user.getSessionsValidFrom());
        assertEquals(Map.of("message", "Sesión cerrada"), response.getBody());
        verify(userRepository).save(user);
        verify(auditLogService).recordEvent("ana", "mobile-logout", "session",
                null, "192.168.1.25", "success");
    }

    @Test
    void firstMobileLoginOnlyIssuesShortLivedTotpEnrollmentToken() {
        AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
        JwtService jwtService = mock(JwtService.class);
        LoginAttemptService loginAttemptService = mock(LoginAttemptService.class);
        AuthRateLimitService authRateLimitService = mock(AuthRateLimitService.class);
        UserRepository userRepository = mock(UserRepository.class);
        SecurityService securityService = mock(SecurityService.class);
        AltchaService altchaService = mock(AltchaService.class);
        PasswordResetService passwordResetService = mock(PasswordResetService.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        AccountRegistrationService accountRegistrationService = mock(AccountRegistrationService.class);
        EmailVerificationService emailVerificationService = mock(EmailVerificationService.class);
        MobileMfaService mobileMfaService = mock(MobileMfaService.class);
        AuthController controller = new AuthController(
                authenticationManager, jwtService, loginAttemptService, authRateLimitService,
                userRepository, securityService, altchaService, passwordResetService,
                auditLogService, accountRegistrationService, emailVerificationService, mobileMfaService);

        User operator = new User();
        operator.setUsername("operador");
        when(authRateLimitService.allowLogin("192.168.1.25")).thenReturn(true);
        when(altchaService.verify("captcha", "login")).thenReturn(true);
        when(userRepository.findByUsername("operador")).thenReturn(Optional.of(operator));
        when(authenticationManager.authenticate(any())).thenReturn(
                new UsernamePasswordAuthenticationToken("operador", null,
                        List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))));
        when(jwtService.generateMobileEnrollmentToken("operador")).thenReturn("setup-token");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("192.168.1.25");

        var response = controller.mobileLogin(
                new MobileLoginRequest("operador", "password", "captcha", null), request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(true, ((Map<?, ?>) response.getBody()).get("mfaSetupRequired"));
        assertEquals("setup-token", ((Map<?, ?>) response.getBody()).get("token"));
        verify(jwtService, never()).generateMobileToken(any());
    }

    @Test
    void enrolledMobileOperatorMustPresentAndConsumeATotpCode() {
        AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
        JwtService jwtService = mock(JwtService.class);
        LoginAttemptService loginAttemptService = mock(LoginAttemptService.class);
        AuthRateLimitService authRateLimitService = mock(AuthRateLimitService.class);
        UserRepository userRepository = mock(UserRepository.class);
        SecurityService securityService = mock(SecurityService.class);
        AltchaService altchaService = mock(AltchaService.class);
        PasswordResetService passwordResetService = mock(PasswordResetService.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        AccountRegistrationService accountRegistrationService = mock(AccountRegistrationService.class);
        EmailVerificationService emailVerificationService = mock(EmailVerificationService.class);
        MobileMfaService mobileMfaService = mock(MobileMfaService.class);
        AuthController controller = new AuthController(
                authenticationManager, jwtService, loginAttemptService, authRateLimitService,
                userRepository, securityService, altchaService, passwordResetService,
                auditLogService, accountRegistrationService, emailVerificationService, mobileMfaService);

        User operator = new User();
        operator.setUsername("operador");
        operator.setMobileTotpEnabled(true);
        when(authRateLimitService.allowLogin("192.168.1.25")).thenReturn(true);
        when(altchaService.verify("captcha", "login")).thenReturn(true);
        when(userRepository.findByUsername("operador")).thenReturn(Optional.of(operator));
        when(authenticationManager.authenticate(any())).thenReturn(
                new UsernamePasswordAuthenticationToken("operador", null,
                        List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))));
        when(mobileMfaService.consumeLoginCode("operador", "123456")).thenReturn(true);
        when(jwtService.generateMobileToken("operador")).thenReturn("mobile-token");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("192.168.1.25");

        var response = controller.mobileLogin(
                new MobileLoginRequest("operador", "password", "captcha", "123456"), request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("mobile-token", ((cl.codes.controller.dto.LoginResponse) response.getBody()).token());
        verify(mobileMfaService).consumeLoginCode("operador", "123456");
    }
}
