package cl.apppolicial.controlador;

import cl.apppolicial.controlador.dto.CrearUsuarioRequest;
import cl.apppolicial.controlador.dto.UsuarioResponse;
import cl.apppolicial.modelo.Usuario;
import cl.apppolicial.repositorio.UsuarioRepository;
import cl.apppolicial.servicio.SeguridadService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Gestión de cuentas de operador. Todo el controlador exige rol
 * ADMINISTRADOR — es intencional que ni supervisores ni operadores
 * puedan crear o modificar cuentas, ni siquiera las suyas.
 */
@RestController
@RequestMapping("/api/usuarios")
@PreAuthorize("hasRole('ADMINISTRADOR')")
public class UsuarioController {

    private final UsuarioRepository repo;
    private final SeguridadService seguridad;

    public UsuarioController(UsuarioRepository repo, SeguridadService seguridad) {
        this.repo = repo;
        this.seguridad = seguridad;
    }

    @GetMapping
    public List<UsuarioResponse> listar() {
        return repo.findAll().stream().map(UsuarioResponse::de).toList();
    }

    @PostMapping
    public UsuarioResponse crear(@Valid @RequestBody CrearUsuarioRequest req) {
        if (repo.existsByNombreUsuario(req.nombreUsuario())) {
            throw new IllegalStateException("Ya existe un usuario con ese nombre");
        }
        Usuario u = new Usuario();
        u.setNombreUsuario(req.nombreUsuario());
        u.setPasswordHash(seguridad.hashPassword(req.password()));
        u.setRol(req.rol());
        u.setActivo(true);
        return UsuarioResponse.de(repo.save(u));
    }

    @PatchMapping("/{id}/desactivar")
    public UsuarioResponse desactivar(@PathVariable Long id, Authentication auth) {
        Usuario u = obtener(id);
        if (u.getNombreUsuario().equals(auth.getName())) {
            throw new IllegalStateException("No puedes desactivar tu propia cuenta");
        }
        u.setActivo(false);
        return UsuarioResponse.de(repo.save(u));
    }

    @PatchMapping("/{id}/activar")
    public UsuarioResponse activar(@PathVariable Long id) {
        Usuario u = obtener(id);
        u.setActivo(true);
        return UsuarioResponse.de(repo.save(u));
    }

    private Usuario obtener(Long id) {
        return repo.findById(id).orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado"));
    }
}
