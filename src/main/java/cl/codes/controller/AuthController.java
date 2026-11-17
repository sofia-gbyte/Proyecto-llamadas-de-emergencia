package cl.codes.controller;

import cl.codes.controller.dto.LoginRequest;
import cl.codes.controller.dto.LoginResponse;
import cl.codes.controller.dto.MobileLoginRequest;
import cl.codes.controller.dto.MobileTotpVerifyRequest;
import cl.codes.controller.dto.RegisterRequest;
import cl.codes.controller.dto.UserResponse;
import cl.codes.exception.ResourceNotFoundException;
import cl.codes.controller.dto.ChangePasswordRequest;
import cl.codes.controller.dto.ForgotPasswordRequest;
import cl.codes.controller.dto.ResetPasswordRequest;
import cl.codes.controller.dto.VerifyEmailRequest;
import cl.codes.exception.EmailDeliveryException;
import cl.codes.service.PasswordResetService;
import cl.codes.model.User;
import cl.codes.repository.UserRepository;
import cl.codes.service.SecurityService;
import cl.codes.service.AuditLogService;
import cl.codes.service.AccountRegistrationService;
import cl.codes.service.EmailVerificationService;
import cl.codes.service.MobileMfaService;
import cl.codes.security.JwtService;
import cl.codes.security.AuthRateLimitService;
import cl.codes.security.LoginAttemptService;
import cl.codes.security.AltchaService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final LoginAttemptService loginAttemptService;
    private final AuthRateLimitService authRateLimitService;
    private final UserRepository userRepository;
    private final SecurityService securityService;
    private final AltchaService altchaService;
    private final PasswordResetService passwordResetService;
    private final AuditLogService auditLogService;
    private final AccountRegistrationService accountRegistrationService;
    private final EmailVerificationService emailVerificationService;
    private final MobileMfaService mobileMfaService;

    public AuthController(
            AuthenticationManager authenticationManager,
            JwtService jwtService,
            LoginAttemptService loginAttemptService,
            AuthRateLimitService authRateLimitService,
            UserRepository userRepository,
            SecurityService securityService,
            AltchaService altchaService,
            PasswordResetService passwordResetService,
            AuditLogService auditLogService,
            AccountRegistrationService accountRegistrationService,
            EmailVerificationService emailVerificationService,
            MobileMfaService mobileMfaService
    ) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.loginAttemptService = loginAttemptService;
        this.authRateLimitService = authRateLimitService;
        this.userRepository = userRepository;
        this.securityService = securityService;
        this.altchaService = altchaService;
        this.passwordResetService = passwordResetService;
        this.auditLogService = auditLogService;
        this.accountRegistrationService = accountRegistrationService;
        this.emailVerificationService = emailVerificationService;
        this.mobileMfaService = mobileMfaService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req, HttpServletRequest request) {
        if (!authRateLimitService.allowLogin(request.getRemoteAddr())) {
            return tooManyRequests();
        }
        if (!altchaService.verify(req.captchaToken(), "login")) {
            return captchaRequired();
        }
        String identifier = req.username().strip();
        String user = userRepository.findByUsername(identifier)
                .or(() -> userRepository.findByEmailIgnoreCase(identifier))
                .map(User::getUsername)
                .orElse(identifier);

        if (loginAttemptService.isBlocked(user)) {
            long minutes = loginAttemptService.remainingBlockMinutes(user);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Too many failed attempts. Try again in " + minutes + " min."));
        }

        try {
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(user, req.password())
            );
            loginAttemptService.registerSuccess(user);

            String role = auth.getAuthorities().iterator().next().getAuthority()
                    .replace("ROLE_", "").toLowerCase();
            String token = jwtService.generateToken(user, role);
            auditLogService.recordEvent(user, "login", "session", null, request.getRemoteAddr(), "success");

            return ResponseEntity.ok(new LoginResponse(token, user, role, jwtService.getExpirationMinutes()));

        } catch (AuthenticationException e) {
            if (!(e instanceof DisabledException)) {
                loginAttemptService.registerFailure(user);
            }
            auditLogService.recordEvent(user, "login", "session", null, request.getRemoteAddr(), "failure");
            return invalidCredentials();
        }
    }

    @PostMapping("/mobile/login")
    public ResponseEntity<?> mobileLogin(@Valid @RequestBody MobileLoginRequest req, HttpServletRequest request) {
        if (!authRateLimitService.allowLogin(request.getRemoteAddr())) {
            return tooManyRequests();
        }
        if (!altchaService.verify(req.captchaToken(), "login")) {
            return captchaRequired();
        }
        String identifier = req.username().strip();
        String username = userRepository.findByUsername(identifier)
                .or(() -> userRepository.findByEmailIgnoreCase(identifier))
                .map(User::getUsername)
                .orElse(identifier);

        if (loginAttemptService.isBlocked(username)) {
            long minutes = loginAttemptService.remainingBlockMinutes(username);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Too many failed attempts. Try again in " + minutes + " min."));
        }

        try {
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, req.password())
            );
            if (!auth.getAuthorities().stream().anyMatch(authority ->
                    "ROLE_OPERATOR".equals(authority.getAuthority()))) {
                auditLogService.recordEvent(username, "mobile-login", "session", null,
                        request.getRemoteAddr(), "rejected-role");
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "La app móvil está disponible solo para cuentas de operador."));
            }
            if (userRepository.findByUsername(username).map(User::isTestAccount).orElse(false)) {
                auditLogService.recordEvent(username, "mobile-login", "session", null,
                        request.getRemoteAddr(), "rejected-test-account");
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "La app móvil no está disponible para cuentas de prueba."));
            }

            User mobileUser = userRepository.findByUsername(username)
                    .orElseThrow(() -> new IllegalStateException("No se encontró la cuenta de operador."));
            if (!mobileUser.isMobileTotpEnabled()) {
                loginAttemptService.registerSuccess(username);
                String enrollmentToken = jwtService.generateMobileEnrollmentToken(username);
                auditLogService.recordEvent(username, "mobile-mfa-enrollment-required", "session", null,
                        request.getRemoteAddr(), "success");
                return ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(Map.of("mfaSetupRequired", true, "token", enrollmentToken, "role", "operator"));
            }
            if (!authRateLimitService.allowLogin(request.getRemoteAddr())
                    || !mobileMfaService.consumeLoginCode(username, req.totpCode())) {
                loginAttemptService.registerFailure(username);
                auditLogService.recordEvent(username, "mobile-login", "session", null,
                        request.getRemoteAddr(), "failure-totp");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .cacheControl(CacheControl.noStore())
                        .body(Map.of("error", "El código de autenticación no es válido.", "mfaRequired", true));
            }

            loginAttemptService.registerSuccess(username);
            String token = jwtService.generateMobileToken(username);
            auditLogService.recordEvent(username, "mobile-login", "session", null,
                    request.getRemoteAddr(), "success");
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(new LoginResponse(token, username, "operator", jwtService.getMobileExpirationMinutes()));
        } catch (AuthenticationException exception) {
            if (!(exception instanceof DisabledException)) {
                loginAttemptService.registerFailure(username);
            }
            auditLogService.recordEvent(username, "mobile-login", "session", null,
                    request.getRemoteAddr(), "failure");
            return invalidCredentials();
        }
    }

    @PostMapping("/mobile/totp/setup")
    @PreAuthorize("hasRole('MOBILE_ENROLLMENT')")
    public ResponseEntity<?> beginMobileTotpSetup(Authentication auth, HttpServletRequest request) {
        if (!authRateLimitService.allowLogin(request.getRemoteAddr())) return tooManyRequests();
        return mobileMfaService.beginEnrollment(auth.getName())
                .<ResponseEntity<?>>map(setup -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(Map.of("secret", setup.secret(), "otpauthUri", setup.otpauthUri())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "La autenticación ya está configurada.")));
    }

    @PostMapping("/mobile/totp/verify-setup")
    @PreAuthorize("hasRole('MOBILE_ENROLLMENT')")
    public ResponseEntity<?> verifyMobileTotpSetup(
            @Valid @RequestBody MobileTotpVerifyRequest req,
            Authentication auth,
            HttpServletRequest request
    ) {
        if (!authRateLimitService.allowLogin(request.getRemoteAddr())) return tooManyRequests();
        if (!mobileMfaService.completeEnrollment(auth.getName(), req.code())) {
            loginAttemptService.registerFailure(auth.getName());
            return ResponseEntity.badRequest()
                    .cacheControl(CacheControl.noStore())
                    .body(Map.of("error", "El código no coincide. Comprueba la hora del teléfono e inténtalo otra vez."));
        }
        loginAttemptService.registerSuccess(auth.getName());
        String token = jwtService.generateMobileToken(auth.getName());
        auditLogService.recordEvent(auth.getName(), "mobile-mfa-enrollment", "session", null,
                request.getRemoteAddr(), "success");
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new LoginResponse(token, auth.getName(), "operator", jwtService.getMobileExpirationMinutes()));
    }

    @PostMapping("/logout")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> logout(Authentication auth, HttpServletRequest request) {
        return invalidateSession(auth.getName(), "logout", request);
    }

    @PostMapping("/mobile/logout")
    @PreAuthorize("hasRole('MOBILE_OPERATOR')")
    public ResponseEntity<?> mobileLogout(Authentication auth, HttpServletRequest request) {
        return invalidateSession(auth.getName(), "mobile-logout", request);
    }

    private ResponseEntity<?> invalidateSession(
            String username,
            String event,
            HttpServletRequest request
    ) {
        // Sesión sin estado (JWT): "cerrar sesión" significa que el token
        // actual -y cualquier otro que el mismo usuario tuviera abierto-
        // deja de aceptarse desde este momento, aunque no haya expirado.
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        user.setSessionsValidFrom(LocalDateTime.now());
        userRepository.save(user);
        auditLogService.recordEvent(user.getUsername(), event, "session", null,
                request.getRemoteAddr(), "success");
        return ResponseEntity.ok(Map.of("message", "Sesión cerrada"));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@Valid @RequestBody ForgotPasswordRequest req, HttpServletRequest request) {
        if (!authRateLimitService.allowRecovery(request.getRemoteAddr())) return tooManyRequests();
        // Respuesta idéntica exista o no la cuenta: evita enumeración de correos.
        try {
            passwordResetService.requestReset(req.email());
        } catch (EmailDeliveryException exception) {
            log.warn("Password recovery email delivery failed: {}", exception.getClass().getSimpleName());
        }
        auditLogService.recordEvent(req.email(), "forgot-password-request", "user", null, request.getRemoteAddr(), "requested");
        return ResponseEntity.ok(Map.of("message", "Si existe una cuenta activa con ese correo, recibirás instrucciones de recuperación."));
    }

    @PostMapping("/verify-email")
    public ResponseEntity<?> verifyEmail(@Valid @RequestBody VerifyEmailRequest req, HttpServletRequest request) {
        if (!authRateLimitService.allowRecovery(request.getRemoteAddr())) return tooManyRequests();
        if (!emailVerificationService.verify(req.token())) {
            auditLogService.recordEvent(null, "verify-email", "user", null, request.getRemoteAddr(), "failure");
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "El enlace de confirmación no es válido, ya fue utilizado o expiró."
            ));
        }
        auditLogService.recordEvent(null, "verify-email", "user", null, request.getRemoteAddr(), "success");
        return ResponseEntity.ok(Map.of(
                "message", "Correo confirmado. Un administrador debe aprobar la cuenta antes de iniciar sesión."
        ));
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<?> resendVerification(@Valid @RequestBody ForgotPasswordRequest req,
                                               HttpServletRequest request) {
        if (!authRateLimitService.allowRecovery(request.getRemoteAddr())) return tooManyRequests();
        try {
            emailVerificationService.resend(req.email());
        } catch (EmailDeliveryException exception) {
            log.warn("Verification email resend failed: {}", exception.getClass().getSimpleName());
        }
        auditLogService.recordEvent(req.email(), "resend-verification-request", "user", null,
                request.getRemoteAddr(), "requested");
        return ResponseEntity.ok(Map.of(
                "message", "Si la cuenta necesita confirmación y el correo está disponible, enviaremos un nuevo enlace."
        ));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@Valid @RequestBody ResetPasswordRequest req, HttpServletRequest request) {
        if (!passwordResetService.resetPassword(req.token(), req.newPassword())) {
            auditLogService.recordEvent(null, "reset-password", "user", null, request.getRemoteAddr(), "failure");
            return ResponseEntity.badRequest().body(Map.of("error", "El enlace de recuperación no es válido, ya fue utilizado o expiró."));
        }
        auditLogService.recordEvent(null, "reset-password", "user", null, request.getRemoteAddr(), "success");
        return ResponseEntity.ok(Map.of("message", "Contraseña restablecida correctamente. Ya puedes iniciar sesión."));
    }

    @org.springframework.web.bind.annotation.PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest req, HttpServletRequest request) {
        if (!authRateLimitService.allowRegister(request.getRemoteAddr())) {
            return tooManyRequests();
        }
        if (!altchaService.verify(req.captchaToken(), "register")) {
            return captchaRequired();
        }
        User account;
        try {
            account = accountRegistrationService.register(req);
        } catch (EmailDeliveryException exception) {
            log.warn("Registration verification email failed: {}", exception.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "No se pudo enviar el correo de confirmación. Revisa la configuración SMTP e inténtalo nuevamente."
            ));
        }
        String user = account.getUsername();
        auditLogService.recordEvent(user, "register", "user", user, request.getRemoteAddr(), "success");
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "message", "Cuenta creada. Confirma tu correo; después, un administrador deberá aprobarla.",
                "user", UserResponse.from(account)
        ));
    }

    @PostMapping("/change-password")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> changePassword(@Valid @RequestBody ChangePasswordRequest req, Authentication auth, HttpServletRequest request) {
        User user = userRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (!securityService.verifyPassword(req.currentPassword(), user.getPasswordHash())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "Current password is incorrect"));
        }
        if (req.currentPassword().equals(req.newPassword())) {
            return ResponseEntity.badRequest().body(Map.of("error", "The new password must be different"));
        }
        user.setPasswordHash(securityService.hashPassword(req.newPassword()));
        // Cualquier JWT emitido antes de ahora deja de servir: si alguien más
        // tenía una sesión abierta con la contraseña anterior, queda fuera.
        user.setSessionsValidFrom(LocalDateTime.now());
        userRepository.save(user);
        auditLogService.recordEvent(user.getUsername(), "change-password", "user", user.getUsername(), request.getRemoteAddr(), "success");
        return ResponseEntity.ok(Map.of("message", "Password changed successfully"));
    }

    @org.springframework.web.bind.annotation.GetMapping("/captcha-config")
    public ResponseEntity<?> captchaConfig() {
        return ResponseEntity.ok(Map.of("enabled", altchaService.isEnabled()));
    }

    @org.springframework.web.bind.annotation.GetMapping("/captcha-challenge")
    public ResponseEntity<?> captchaChallenge(
            @RequestParam String purpose,
            HttpServletRequest request
    ) {
        if (!authRateLimitService.allowCaptcha(request.getRemoteAddr())) {
            return tooManyRequests();
        }
        if (!AltchaService.isSupportedPurpose(purpose)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unsupported CAPTCHA purpose."));
        }
        if (!altchaService.isEnabled()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(altchaService.createChallenge(purpose));
    }

    private ResponseEntity<?> invalidCredentials() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Invalid username or password"));
    }

    private ResponseEntity<?> tooManyRequests() {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("error", "Too many requests. Try again later."));
    }

    private ResponseEntity<?> captchaRequired() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Captcha inválido o no configurado."));
    }
}
