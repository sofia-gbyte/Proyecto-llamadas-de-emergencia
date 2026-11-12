package cl.codes.controller;

import cl.codes.controller.dto.CreateUserRequest;
import cl.codes.controller.dto.UpdateUserRequest;
import cl.codes.controller.dto.UserResponse;
import cl.codes.exception.ResourceNotFoundException;
import cl.codes.model.User;
import cl.codes.repository.UserRepository;
import cl.codes.service.AuditLogService;
import cl.codes.service.SecurityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMINISTRATOR')")
public class UserController {

    private final UserRepository repo;
    private final SecurityService securityService;
    private final AuditLogService auditLogService;

    public UserController(UserRepository repo, SecurityService securityService, AuditLogService auditLogService) {
        this.repo = repo;
        this.securityService = securityService;
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public List<UserResponse> list() {
        return repo.findAll().stream().map(UserResponse::from).toList();
    }

    @GetMapping("/{id}")
    public UserResponse getById(@PathVariable Long id) {
        return UserResponse.from(get(id));
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest req, Authentication auth, HttpServletRequest request) {
        if (repo.existsByUsername(req.username())) {
            throw new IllegalStateException("A user with that username already exists");
        }
        User user = new User();
        user.setUsername(req.username());
        user.setPasswordHash(securityService.hashPassword(req.password()));
        user.setRole(req.rol());
        user.setActive(true);
        user.setInstitution(req.institution());
        User createdUser = repo.save(user);
        auditLogService.recordEvent(auth.getName(), "create-user", "user", createdUser.getUsername(), request.getRemoteAddr(), "success");
        var location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/users/{id}")
                .buildAndExpand(createdUser.getId())
                .toUri();
        return ResponseEntity.created(location).body(UserResponse.from(createdUser));
    }

    @PutMapping("/{id}")
    public UserResponse update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateUserRequest req,
            Authentication auth,
            HttpServletRequest request
    ) {
        User user = get(id);
        if (req.email() != null && !req.email().isBlank()
                && repo.existsByEmailIgnoreCaseAndIdNot(req.email().strip(), id)) {
            throw new IllegalStateException("An account with that email already exists");
        }
        if (user.isActive()
                && "administrator".equalsIgnoreCase(user.getRole())
                && !"administrator".equalsIgnoreCase(req.rol())
                && repo.countByRoleIgnoreCaseAndActiveTrue("administrator") <= 1) {
            throw new IllegalStateException("At least one active administrator must remain");
        }

        user.setRole(req.rol());
        user.setInstitution(req.institution());
        user.setFirstName(normalizeOptional(req.firstName()));
        user.setLastName(normalizeOptional(req.lastName()));
        user.setEmail(normalizeOptional(req.email()));
        user.setSessionsValidFrom(LocalDateTime.now());
        UserResponse response = UserResponse.from(repo.save(user));
        auditLogService.recordEvent(auth.getName(), "update-user", "user", user.getUsername(), request.getRemoteAddr(), "success");
        return response;
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, Authentication auth, HttpServletRequest request) {
        User user = get(id);
        if (user.getUsername().equals(auth.getName())) {
            throw new IllegalStateException("You cannot delete your own account");
        }
        if (user.isActive()
                && "administrator".equalsIgnoreCase(user.getRole())
                && repo.countByRoleIgnoreCaseAndActiveTrue("administrator") <= 1) {
            throw new IllegalStateException("At least one active administrator must remain");
        }
        String username = user.getUsername();
        repo.delete(user);
        auditLogService.recordEvent(auth.getName(), "delete-user", "user", username, request.getRemoteAddr(), "success");
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/disable")
    public UserResponse disable(@PathVariable Long id, Authentication auth, HttpServletRequest request) {
        User user = get(id);
        if (user.getUsername().equals(auth.getName())) {
            throw new IllegalStateException("You cannot disable your own account");
        }
        user.setActive(false);
        // Además de bloquear el acceso, invalida cualquier JWT que ese
        // usuario tuviera abierto en ese momento.
        user.setSessionsValidFrom(LocalDateTime.now());
        UserResponse response = UserResponse.from(repo.save(user));
        auditLogService.recordEvent(auth.getName(), "disable-user", "user", user.getUsername(), request.getRemoteAddr(), "success");
        return response;
    }

    @PatchMapping("/{id}/enable")
    public UserResponse enable(@PathVariable Long id, Authentication auth, HttpServletRequest request) {
        User user = get(id);
        user.setActive(true);
        UserResponse response = UserResponse.from(repo.save(user));
        auditLogService.recordEvent(auth.getName(), "enable-user", "user", user.getUsername(), request.getRemoteAddr(), "success");
        return response;
    }

    /**
     * Restablece la contraseña de un usuario a una clave temporal aleatoria,
     * que el administrador debe comunicarle en persona (o por el canal
     * interno que usen). Existe porque, sin SMTP configurado, el flujo de
     * "olvidé mi contraseña" por correo no tiene forma de completarse: esta
     * es la vía de respaldo mientras no haya proveedor de correo.
     */
    @PatchMapping("/{id}/reset-password")
    public java.util.Map<String, Object> resetPassword(@PathVariable Long id, Authentication auth, HttpServletRequest request) {
        User user = get(id);
        String temporaryPassword = generateTemporaryPassword();
        user.setPasswordHash(securityService.hashPassword(temporaryPassword));
        user.setSessionsValidFrom(LocalDateTime.now());
        repo.save(user);
        auditLogService.recordEvent(auth.getName(), "admin-reset-password", "user", user.getUsername(), request.getRemoteAddr(), "success");
        return java.util.Map.of(
                "message", "Contraseña temporal generada. Comunícasela a " + user.getUsername() + " por un canal seguro; debería cambiarla al iniciar sesión.",
                "temporaryPassword", temporaryPassword
        );
    }

    private String generateTemporaryPassword() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        java.security.SecureRandom random = new java.security.SecureRandom();
        StringBuilder password = new StringBuilder(12);
        for (int i = 0; i < 12; i++) password.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return password.toString();
    }

    /**
     * Cierra las sesiones activas de un usuario sin desactivar su cuenta:
     * útil si se sospecha que un JWT quedó expuesto pero la persona sigue
     * trabajando (por ejemplo, cambió de turno y olvidó cerrar sesión en
     * un equipo compartido de la sala).
     */
    @PatchMapping("/{id}/close-sessions")
    public UserResponse closeSessions(@PathVariable Long id, Authentication auth, HttpServletRequest request) {
        User user = get(id);
        user.setSessionsValidFrom(LocalDateTime.now());
        UserResponse response = UserResponse.from(repo.save(user));
        auditLogService.recordEvent(auth.getName(), "close-sessions", "user", user.getUsername(), request.getRemoteAddr(), "success");
        return response;
    }

    private User get(Long id) {
        return repo.findById(Objects.requireNonNull(id)).orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
