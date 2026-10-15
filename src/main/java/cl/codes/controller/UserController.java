package cl.codes.controller;

import cl.codes.controller.dto.CreateUserRequest;
import cl.codes.controller.dto.UserResponse;
import cl.codes.model.User;
import cl.codes.repository.UserRepository;
import cl.codes.service.AuditLogService;
import cl.codes.service.SecurityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

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
        return repo.findAll().stream().map(UserResponse::de).toList();
    }

    @PostMapping
    public UserResponse create(@Valid @RequestBody CreateUserRequest req, Authentication auth, HttpServletRequest request) {
        if (repo.existsByUsername(req.username())) {
            throw new IllegalStateException("A user with that username already exists");
        }
        User user = new User();
        user.setUsername(req.username());
        user.setPasswordHash(securityService.hashPassword(req.password()));
        user.setRole(req.rol());
        user.setActive(true);
        user.setInstitution(req.institution());
        User creado = repo.save(user);
        auditLogService.registrar(auth.getName(), "create-user", "user", creado.getUsername(), request.getRemoteAddr(), "success");
        return UserResponse.de(creado);
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
        UserResponse resultado = UserResponse.de(repo.save(user));
        auditLogService.registrar(auth.getName(), "disable-user", "user", user.getUsername(), request.getRemoteAddr(), "success");
        return resultado;
    }

    @PatchMapping("/{id}/enable")
    public UserResponse enable(@PathVariable Long id, Authentication auth, HttpServletRequest request) {
        User user = get(id);
        user.setActive(true);
        UserResponse resultado = UserResponse.de(repo.save(user));
        auditLogService.registrar(auth.getName(), "enable-user", "user", user.getUsername(), request.getRemoteAddr(), "success");
        return resultado;
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
        String temporal = generarClaveTemporal();
        user.setPasswordHash(securityService.hashPassword(temporal));
        user.setSessionsValidFrom(LocalDateTime.now());
        repo.save(user);
        auditLogService.registrar(auth.getName(), "admin-reset-password", "user", user.getUsername(), request.getRemoteAddr(), "success");
        return java.util.Map.of(
                "message", "Contraseña temporal generada. Comunícasela a " + user.getUsername() + " por un canal seguro; debería cambiarla al iniciar sesión.",
                "temporaryPassword", temporal
        );
    }

    private String generarClaveTemporal() {
        String alfabeto = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        java.security.SecureRandom random = new java.security.SecureRandom();
        StringBuilder clave = new StringBuilder(12);
        for (int i = 0; i < 12; i++) clave.append(alfabeto.charAt(random.nextInt(alfabeto.length())));
        return clave.toString();
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
        UserResponse resultado = UserResponse.de(repo.save(user));
        auditLogService.registrar(auth.getName(), "close-sessions", "user", user.getUsername(), request.getRemoteAddr(), "success");
        return resultado;
    }

    private User get(Long id) {
        return repo.findById(id).orElseThrow(() -> new IllegalArgumentException("User not found"));
    }
}
