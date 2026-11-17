package cl.codes.security;

import cl.codes.model.User;
import cl.codes.repository.UserRepository;
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

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.equals("/api/auth/login")
                || path.equals("/api/auth/mobile/login")
                || path.equals("/api/auth/register")
                || path.equals("/api/health")
                || path.equals("/api/heartbeat")
                || path.equals("/")
                || path.equals("/index.html")
                || path.equals("/styles.css")
                || path.equals("/script.js");
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
            Claims claims = jwtService.validateAndGetClaims(token);

            if (claims != null) {
                String username = claims.getSubject();
                String client = claims.get("client", String.class);
                boolean mobileClient = "mobile".equals(client);
                boolean mobileEnrollment = Boolean.TRUE.equals(claims.get("mobile_enrollment", Boolean.class));
                boolean unknownClient = client != null && !mobileClient;
                boolean mobileLogout = "/api/auth/mobile/logout".equals(request.getServletPath());
                String path = request.getServletPath();
                boolean mobileTotpPath = path.equals("/api/auth/mobile/totp/setup")
                        || path.equals("/api/auth/mobile/totp/verify-setup");
                if (mobileClient && (mobileEnrollment
                        ? !mobileTotpPath
                        : !path.startsWith("/api/mobile/") && !mobileLogout && !mobileTotpPath)) {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN);
                    return;
                }
                Optional<User> user = userRepository.findByUsername(username);

                if (!unknownClient && user.isPresent() && user.get().isActive()
                        && isSessionStillValid(user.get(), claims)) {
                    String role = user.get().getRole();
                    if (mobileClient && !"operator".equalsIgnoreCase(role)) {
                        response.sendError(HttpServletResponse.SC_FORBIDDEN);
                        return;
                    }
                    String authority = mobileEnrollment
                            ? "ROLE_MOBILE_ENROLLMENT"
                            : mobileClient ? "ROLE_MOBILE_OPERATOR" : "ROLE_" + role.toUpperCase();
                    List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(authority));

                    var auth = new UsernamePasswordAuthenticationToken(username, null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(auth);
                } else if (unknownClient) {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN);
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * Un JWT emitido antes de {@code sessionsValidFrom} quedó invalidado
     * (logout, cambio de contraseña, o un administrador cerrando sesiones).
     * Si el usuario no tiene esa marca, no hay restricción adicional.
     */
    private boolean isSessionStillValid(User user, Claims claims) {
        if (user.getSessionsValidFrom() == null) return true;
        java.util.Date emitido = claims.getIssuedAt();
        if (emitido == null) return true;
        java.time.LocalDateTime emitidoEn = java.time.LocalDateTime.ofInstant(emitido.toInstant(), java.time.ZoneId.systemDefault());
        return !emitidoEn.isBefore(user.getSessionsValidFrom());
    }
}

