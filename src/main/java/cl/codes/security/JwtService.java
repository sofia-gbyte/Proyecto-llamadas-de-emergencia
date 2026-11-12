package cl.codes.security;

import cl.codes.config.LocalSecretBootstrap;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;

/**
 * Issues and validates session JWTs.
 */
@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long sessionExpirationMinutes;

    public JwtService(
            @Value("${app.jwt-secret:}") String base64Secret,
            @Value("${app.jwt-expiracion-minutos:480}") long sessionExpirationMinutes // 8 horas = un turno
    ) {
        String resolvedSecret = LocalSecretBootstrap.resolveOrGenerate(
                "CODES_JWT_SECRET",
                base64Secret,
                JwtService::generateSecret
        );

        if (resolvedSecret == null || resolvedSecret.isBlank()) {
            throw new IllegalStateException(
                "Falta la variable de entorno CODES_JWT_SECRET. " +
                "No se pudo generar o recuperar un secreto válido para JWT.");
        }

        try {
            this.signingKey = Keys.hmacShaKeyFor(Base64.getDecoder().decode(resolvedSecret));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("La variable CODES_JWT_SECRET no es un valor Base64 válido para JWT.", e);
        }
        this.sessionExpirationMinutes = sessionExpirationMinutes;
    }

    /** Genera un secreto HMAC-SHA256 nuevo, codificado en Base64. */
    public static String generateSecret() {
        SecretKey k = Keys.secretKeyFor(io.jsonwebtoken.SignatureAlgorithm.HS256);
        return Base64.getEncoder().encodeToString(k.getEncoded());
    }

    public String generateToken(String username, String role) {
        return generateToken(username, role, sessionExpirationMinutes);
    }

    /**
     * Igual que {@link #generateToken(String, String)} pero con una
     * expiración propia, más corta que la sesión normal de turno. Se usa
     * para las sesiones de "vista previa" que un ADMINISTRATOR abre para
     * ver la interfaz de una institución sin credenciales reales.
     */
    public String generateToken(String username, String role, long expirationMinutes) {
        Date now = new Date();
        Date expiresAt = new Date(now.getTime() + expirationMinutes * 60_000);

        return Jwts.builder()
                .subject(username)
                .claim("rol", role)
                .issuedAt(now)
                .expiration(expiresAt)
                .signWith(signingKey)
                .compact();
    }

    public Claims validateAndGetClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    public long getExpirationMinutes() {
        return sessionExpirationMinutes;
    }
}
