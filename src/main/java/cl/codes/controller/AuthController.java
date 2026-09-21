package cl.codes.controller;

import cl.codes.controller.dto.LoginRequest;
import cl.codes.controller.dto.LoginResponse;
import cl.codes.controller.dto.RegisterRequest;
import cl.codes.controller.dto.UserResponse;
import cl.codes.model.User;
import cl.codes.repository.UserRepository;
import cl.codes.service.SecurityService;
import cl.codes.security.JwtService;
import cl.codes.security.AuthRateLimitService;
import cl.codes.security.LoginAttemptService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

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

    public AuthController(
            AuthenticationManager authenticationManager,
            JwtService jwtService,
            LoginAttemptService loginAttemptService,
            AuthRateLimitService authRateLimitService,
            UserRepository userRepository,
            SecurityService securityService
    ) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.loginAttemptService = loginAttemptService;
        this.authRateLimitService = authRateLimitService;
        this.userRepository = userRepository;
        this.securityService = securityService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req, HttpServletRequest request) {
        if (!authRateLimitService.allowLogin(request.getRemoteAddr())) {
            return tooManyRequests();
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

            return ResponseEntity.ok(new LoginResponse(token, user, role, jwtService.getExpirationMinutes()));

        } catch (AuthenticationException e) {
            if (!(e instanceof DisabledException)) {
                loginAttemptService.registerFailure(user);
            }
            return invalidCredentials();
        }
    }

    @org.springframework.web.bind.annotation.PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest req, HttpServletRequest request) {
        if (!authRateLimitService.allowRegister(request.getRemoteAddr())) {
            return tooManyRequests();
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
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "message", "Account created successfully. An administrator must activate it before login.",
                "user", UserResponse.de(account)
        ));
    }

    private ResponseEntity<?> invalidCredentials() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Invalid username or password"));
    }

    private ResponseEntity<?> tooManyRequests() {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("error", "Too many requests. Try again later."));
    }
}


