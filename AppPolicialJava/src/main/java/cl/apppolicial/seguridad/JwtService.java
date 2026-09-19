package cl.apppolicial.seguridad;

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
 * Emite y valida los JWT de sesión. Son tokens SIN ESTADO (stateless):
 * el servidor no guarda sesiones, solo firma y verifica.
 *
 * La clave de firma (APP_POLICIAL_JWT_SECRET) es distinta de la clave
 * de cifrado de audios (APP_POLICIAL_ENCRYPT_KEY) — nunca reutilizar la
 * misma clave para dos propósitos criptográficos distintos.
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
                "Falta la variable de entorno APP_POLICIAL_JWT_SECRET. " +
                "Genérala una vez con JwtService.generarSecreto() y expórtala " +
                "antes de iniciar el servicio. Sin esto, la API no puede " +
                "emitir ni validar sesiones de forma segura.");
        }
        this.clave = Keys.hmacShaKeyFor(Base64.getDecoder().decode(secretoBase64));
        this.expiracionMinutos = expiracionMinutos;
    }

    /** Genera un secreto HMAC-SHA256 nuevo, codificado en Base64. */
    public static String generarSecreto() {
        SecretKey k = Keys.secretKeyFor(io.jsonwebtoken.SignatureAlgorithm.HS256);
        return Base64.getEncoder().encodeToString(k.getEncoded());
    }

    public String generarToken(String nombreUsuario, String rol) {
        Date ahora = new Date();
        Date expira = new Date(ahora.getTime() + expiracionMinutos * 60_000);

        return Jwts.builder()
                .subject(nombreUsuario)
                .claim("rol", rol)
                .issuedAt(ahora)
                .expiration(expira)
                .signWith(clave)
                .compact();
    }

    /** Devuelve los claims si el token es válido, o vacío si no lo es
     *  (expirado, firma inválida, malformado, etc.). Nunca lanza excepción
     *  hacia el llamador: un token inválido simplemente no autentica. */
    public Claims validarYObtenerClaims(String token) {
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

    public long getExpiracionMinutos() {
        return expiracionMinutos;
    }
}
