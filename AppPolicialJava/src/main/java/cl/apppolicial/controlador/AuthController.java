package cl.apppolicial.controlador;

import cl.apppolicial.controlador.dto.LoginRequest;
import cl.apppolicial.controlador.dto.RegistroRequest;
import cl.apppolicial.controlador.dto.UsuarioResponse;
import cl.apppolicial.modelo.Usuario;
import cl.apppolicial.repositorio.UsuarioRepository;
import cl.apppolicial.servicio.SeguridadService;
import cl.apppolicial.controlador.dto.LoginResponse;
import cl.apppolicial.seguridad.JwtService;
import cl.apppolicial.seguridad.LoginAttemptService;
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

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final LoginAttemptService loginAttemptService;
    private final UsuarioRepository usuarioRepository;
    private final SeguridadService seguridadService;

    public AuthController(
            AuthenticationManager authenticationManager,
            JwtService jwtService,
            LoginAttemptService loginAttemptService,
            UsuarioRepository usuarioRepository,
            SeguridadService seguridadService
    ) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.loginAttemptService = loginAttemptService;
        this.usuarioRepository = usuarioRepository;
        this.seguridadService = seguridadService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req) {
        String identificador = req.nombreUsuario().strip();
        String usuario = usuarioRepository.findByNombreUsuario(identificador)
                .or(() -> usuarioRepository.findByCorreoIgnoreCase(identificador))
                .map(Usuario::getNombreUsuario)
                .orElse(identificador);

        if (loginAttemptService.estaBloqueado(usuario)) {
            long minutos = loginAttemptService.minutosRestantesBloqueo(usuario);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Demasiados intentos fallidos. Intenta nuevamente en " + minutos + " min."));
        }

        try {
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(usuario, req.password())
            );
            loginAttemptService.registrarExito(usuario);

            String rol = auth.getAuthorities().iterator().next().getAuthority()
                    .replace("ROLE_", "").toLowerCase();
            String token = jwtService.generarToken(usuario, rol);

            return ResponseEntity.ok(new LoginResponse(token, usuario, rol, jwtService.getExpiracionMinutos()));

        } catch (AuthenticationException e) {
            // Las cuentas desactivadas tampoco cuentan como ataque de fuerza
            // bruta. El mensaje sigue siendo genérico para no revelar estados.
            if (!(e instanceof DisabledException)) {
                loginAttemptService.registrarFallo(usuario);
            }
            return credencialesInvalidas();
        }
    }

    @org.springframework.web.bind.annotation.PostMapping("/register")
    public ResponseEntity<?> registrar(@Valid @RequestBody RegistroRequest req) {
        String usuario = req.nombreUsuario().strip();
        String correo = req.correo().strip().toLowerCase();
        if (usuarioRepository.existsByNombreUsuario(usuario)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Ya existe un usuario con ese nombre"));
        }
        if (usuarioRepository.existsByCorreoIgnoreCase(correo)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Ya existe una cuenta con ese correo"));
        }
        Usuario u = new Usuario();
        u.setNombreUsuario(usuario);
        u.setPasswordHash(seguridadService.hashPassword(req.password()));
        u.setRol("operador");
        u.setActivo(false);
        u.setNombre(req.nombre().strip());
        u.setApellido(req.apellido().strip());
        u.setCorreo(correo);
        u.setInstitucion(req.institucion());
        usuarioRepository.save(u);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "mensaje", "Cuenta creada correctamente. Un administrador debe activarla antes de iniciar sesión.",
                "usuario", UsuarioResponse.de(u)
        ));
    }

    private ResponseEntity<?> credencialesInvalidas() {
        // Mensaje deliberadamente genérico: no revela si el usuario existe,
        // si la contraseña es incorrecta, o si la cuenta está desactivada.
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Usuario o contraseña incorrectos"));
    }
}
