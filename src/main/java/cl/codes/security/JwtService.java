package cl.codes.security;

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

    private final SecretKey clave;
    private final long expiracionMinutos;

    public JwtService(
            @Value("${app.jwt-secret:}") String secretoBase64,
            @Value("${app.jwt-expiracion-minutos:480}") long expiracionMinutos // 8 horas = un turno
    ) {
        if (secretoBase64 == null || secretoBase64.isBlank()) {
            throw new IllegalStateException(
                "Falta la variable de entorno CODES_JWT_SECRET. " +
                "Genérala una vez con JwtService.generateSecreto() y expórtala " +
                "antes de iniciar el servicio. Sin esto, la API no puede " +
                "emitir ni validate sesiones de forma segura.");
        }
        this.clave = Keys.hmacShaKeyFor(Base64.getDecoder().decode(secretoBase64));
        this.expiracionMinutos = expiracionMinutos;
    }

    /** Genera un secreto HMAC-SHA256 nuevo, codificado en Base64. */
    public static String generateSecret() {
        SecretKey k = Keys.secretKeyFor(io.jsonwebtoken.SignatureAlgorithm.HS256);
        return Base64.getEncoder().encodeToString(k.getEncoded());
    }

    public String generateToken(String username, String role) {
        Date now = new Date();
        Date expiresAt = new Date(now.getTime() + expiracionMinutos * 60_000);

        return Jwts.builder()
                .subject(username)
                .claim("rol", role)
                .issuedAt(now)
                .expiration(expiresAt)
                .signWith(clave)
                .compact();
    }

    public Claims validateAndGetClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(clave)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    public long getExpirationMinutes() {
        return expiracionMinutos;
    }
}


