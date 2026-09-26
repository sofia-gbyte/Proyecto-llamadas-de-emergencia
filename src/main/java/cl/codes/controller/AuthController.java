package cl.codes.controller;

import cl.codes.controller.dto.LoginRequest;
import cl.codes.controller.dto.LoginResponse;
import cl.codes.controller.dto.RegisterRequest;
import cl.codes.controller.dto.UserResponse;
import cl.codes.controller.dto.ChangePasswordRequest;
import cl.codes.controller.dto.ForgotPasswordRequest;
import cl.codes.controller.dto.ResetPasswordRequest;
import cl.codes.service.PasswordResetService;
import cl.codes.model.User;
import cl.codes.repository.UserRepository;
import cl.codes.service.SecurityService;
import cl.codes.service.AuditLogService;
import cl.codes.security.JwtService;
import cl.codes.security.AuthRateLimitService;
import cl.codes.security.LoginAttemptService;
import cl.codes.security.TurnstileService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final LoginAttemptService loginAttemptService;
    private final AuthRateLimitService authRateLimitService;
    private final UserRepository userRepository;
    private final SecurityService securityService;
    private final TurnstileService turnstileService;
    private final PasswordResetService passwordResetService;
    private final AuditLogService auditLogService;

    public AuthController(
            AuthenticationManager authenticationManager,
            JwtService jwtService,
            LoginAttemptService loginAttemptService,
            AuthRateLimitService authRateLimitService,
            UserRepository userRepository,
            SecurityService securityService,
            TurnstileService turnstileService,
            PasswordResetService passwordResetService,
            AuditLogService auditLogService
    ) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.loginAttemptService = loginAttemptService;
        this.authRateLimitService = authRateLimitService;
        this.userRepository = userRepository;
        this.securityService = securityService;
        this.turnstileService = turnstileService;
        this.passwordResetService = passwordResetService;
        this.auditLogService = auditLogService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req, HttpServletRequest request) {
        if (!authRateLimitService.allowLogin(request.getRemoteAddr())) {
            return tooManyRequests();
        }
        if (!turnstileService.verify(req.captchaToken(), request.getRemoteAddr())) {
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
            auditLogService.registrar(user, "login", "session", null, request.getRemoteAddr(), "success");

            return ResponseEntity.ok(new LoginResponse(token, user, role, jwtService.getExpirationMinutes()));

        } catch (AuthenticationException e) {
            if (!(e instanceof DisabledException)) {
                loginAttemptService.registerFailure(user);
            }
            auditLogService.registrar(user, "login", "session", null, request.getRemoteAddr(), "failure");
            return invalidCredentials();
        }
    }

    @PostMapping("/logout")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> logout(Authentication auth, HttpServletRequest request) {
        // Sesión sin estado (JWT): "cerrar sesión" significa que el token
        // actual -y cualquier otro que el mismo usuario tuviera abierto-
        // deja de aceptarse desde este momento, aunque no haya expirado.
        User user = userRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        user.setSessionsValidFrom(LocalDateTime.now());
        userRepository.save(user);
        auditLogService.registrar(user.getUsername(), "logout", "session", null, request.getRemoteAddr(), "success");
        return ResponseEntity.ok(Map.of("message", "Sesión cerrada"));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@Valid @RequestBody ForgotPasswordRequest req, HttpServletRequest request) {
        if (!authRateLimitService.allowRecovery(request.getRemoteAddr())) return tooManyRequests();
        // Respuesta idéntica exista o no la cuenta: evita enumeración de correos.
        try { passwordResetService.requestReset(req.email()); } catch (RuntimeException ignored) {
            // No revelar si el correo existe ni detalles del proveedor SMTP.
        }
        auditLogService.registrar(req.email(), "forgot-password-request", "user", null, request.getRemoteAddr(), "requested");
        return ResponseEntity.ok(Map.of("message", "Si existe una cuenta activa con ese correo, recibirás instrucciones de recuperación."));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@Valid @RequestBody ResetPasswordRequest req, HttpServletRequest request) {
        if (!passwordResetService.resetPassword(req.token(), req.newPassword())) {
            auditLogService.registrar(null, "reset-password", "user", null, request.getRemoteAddr(), "failure");
            return ResponseEntity.badRequest().body(Map.of("error", "El enlace de recuperación no es válido, ya fue utilizado o expiró."));
        }
        auditLogService.registrar(null, "reset-password", "user", null, request.getRemoteAddr(), "success");
        return ResponseEntity.ok(Map.of("message", "Contraseña restablecida correctamente. Ya puedes iniciar sesión."));
    }

    @org.springframework.web.bind.annotation.PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest req, HttpServletRequest request) {
        if (!authRateLimitService.allowRegister(request.getRemoteAddr())) {
            return tooManyRequests();
        }
        if (!turnstileService.verify(req.captchaToken(), request.getRemoteAddr())) {
            return captchaRequired();
        }
        String user = req.username().strip();
        String email = req.correo().strip().toLowerCase();
        if (userRepository.existsByUsername(user)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "A user with that username already exists"));
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "An account with that email already exists"));
        }
        User account = new User();
        account.setUsername(user);
        account.setPasswordHash(securityService.hashPassword(req.password()));
        account.setRole("operator");
        account.setActive(false);
        account.setFirstName(req.nombre().strip());
        account.setLastName(req.apellido().strip());
        account.setEmail(email);
        account.setInstitution(req.institucion());
        userRepository.save(account);
        auditLogService.registrar(user, "register", "user", user, request.getRemoteAddr(), "success");
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "message", "Account created successfully. An administrator must activate it before login.",
                "user", UserResponse.de(account)
        ));
    }

    @PostMapping("/change-password")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> changePassword(@Valid @RequestBody ChangePasswordRequest req, Authentication auth, HttpServletRequest request) {
        User user = userRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
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
        auditLogService.registrar(user.getUsername(), "change-password", "user", user.getUsername(), request.getRemoteAddr(), "success");
        return ResponseEntity.ok(Map.of("message", "Password changed successfully"));
    }

    @org.springframework.web.bind.annotation.RequestMapping(
            value = "/captcha-site-key",
            method = {org.springframework.web.bind.annotation.RequestMethod.GET,
                      org.springframework.web.bind.annotation.RequestMethod.POST}
    )
    public ResponseEntity<?> captchaSiteKey() {
        return ResponseEntity.ok(Map.of("siteKey", turnstileService.getSiteKey()));
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
