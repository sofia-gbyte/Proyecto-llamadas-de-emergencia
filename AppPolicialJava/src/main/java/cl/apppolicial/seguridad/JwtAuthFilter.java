package cl.apppolicial.seguridad;

import cl.apppolicial.modelo.Usuario;
import cl.apppolicial.repositorio.UsuarioRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Extrae y valida el JWT del header "Authorization: Bearer &lt;token&gt;"
 * en cada petición. Si el token es válido, deja al usuario autenticado
 * en el SecurityContext para el resto de la cadena de filtros; si no
 * hay token o es inválido, simplemente continúa sin autenticar (la
 * decisión de exigir autenticación la toma SecurityConfig por ruta).
 *
 * IMPORTANTE sobre revocación: un JWT es válido hasta que expira, el
 * servidor no puede "invalidarlo" a mitad de camino solo con la firma.
 * Para que desactivar a un operador surta efecto de inmediato (y no
 * recién cuando expire su token, hasta 8h después), se vuelve a
 * consultar en la base de datos si el usuario sigue activo en CADA
 * petición. Para el volumen de una sola comisaría esto es barato; si
 * este sistema creciera a muchas instancias, convendría cachear ese
 * chequeo con un TTL corto en vez de ir a la base en cada request.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UsuarioRepository usuarioRepository;

    public JwtAuthFilter(JwtService jwtService, UsuarioRepository usuarioRepository) {
        this.jwtService = jwtService;
        this.usuarioRepository = usuarioRepository;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain chain
    ) throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            Claims claims = jwtService.validarYObtenerClaims(token);

            if (claims != null) {
                String nombreUsuario = claims.getSubject();
                Optional<Usuario> usuario = usuarioRepository.findByNombreUsuario(nombreUsuario);

                // Revalidar contra la base: si el usuario fue desactivado o
                // borrado después de emitirse el token, no autenticar aunque
                // la firma siga siendo válida.
                if (usuario.isPresent() && usuario.get().isActivo()) {
                    // Se usa el rol vigente en la base, no el que venía en el
                    // token, para que un cambio de rol también sea inmediato.
                    String rol = usuario.get().getRol();
                    List<GrantedAuthority> autoridades =
                            List.of(new SimpleGrantedAuthority("ROLE_" + rol.toUpperCase()));

                    var auth = new UsernamePasswordAuthenticationToken(nombreUsuario, null, autoridades);
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }
        }
        chain.doFilter(request, response);
    }
}
