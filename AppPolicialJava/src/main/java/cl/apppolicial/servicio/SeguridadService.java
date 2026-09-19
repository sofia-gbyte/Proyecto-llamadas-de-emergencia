package cl.apppolicial.servicio;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Cifrado de audios en reposo con AES-256-GCM (autenticado: protege
 * tanto confidencialidad como integridad, a diferencia de un AES-CBC
 * simple) y hash de contraseñas de operadores con bcrypt (delegado al
 * PasswordEncoder configurado en SecurityConfig, para que sea la única
 * fuente de verdad del costo de hashing en toda la app).
 *
 * La clave de cifrado se toma de la variable de entorno
 * APP_POLICIAL_ENCRYPT_KEY (ver application.properties: app.encrypt-key).
 * Nunca debe quedar hardcodeada en el código fuente.
 */
@Service
public class SeguridadService {

    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;

    private final String claveBase64;
    private final PasswordEncoder passwordEncoder;

    public SeguridadService(
            @Value("${app.encrypt-key:}") String claveBase64,
            PasswordEncoder passwordEncoder
    ) {
        this.claveBase64 = claveBase64;
        this.passwordEncoder = passwordEncoder;
    }

    /** Genera una clave AES-256 nueva, codificada en Base64. Ejecutar UNA
     *  vez y guardar el resultado como variable de entorno; no regenerar,
     *  o los audios ya cifrados quedarán ilegibles. */
    public static String generarClave() {
        byte[] bytes = new byte[32]; // 256 bits
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private SecretKey obtenerClave() {
        if (claveBase64 == null || claveBase64.isBlank()) {
            throw new IllegalStateException(
                "Falta la variable de entorno APP_POLICIAL_ENCRYPT_KEY. " +
                "Genérala una vez con SeguridadService.generarClave() y expórtala " +
                "antes de iniciar el servicio.");
        }
        byte[] bytes = Base64.getDecoder().decode(claveBase64);
        return new SecretKeySpec(bytes, "AES");
    }

    public void encriptarArchivo(Path origen, Path destino) throws IOException {
        try {
            byte[] datos = Files.readAllBytes(origen);
            byte[] iv = new byte[GCM_IV_BYTES];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, obtenerClave(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] cifrado = cipher.doFinal(datos);

            // Guardamos [iv (12 bytes)][texto cifrado + tag] en un solo archivo.
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + cifrado.length);
            buffer.put(iv).put(cifrado);
            Files.write(destino, buffer.array());
            Files.delete(origen);
        } catch (Exception e) {
            throw new IOException("Error cifrando archivo: " + e.getMessage(), e);
        }
    }

    public void desencriptarArchivo(Path origen, Path destino) throws IOException {
        try {
            byte[] contenido = Files.readAllBytes(origen);
            byte[] iv = new byte[GCM_IV_BYTES];
            System.arraycopy(contenido, 0, iv, 0, GCM_IV_BYTES);
            byte[] cifrado = new byte[contenido.length - GCM_IV_BYTES];
            System.arraycopy(contenido, GCM_IV_BYTES, cifrado, 0, cifrado.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, obtenerClave(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] datos = cipher.doFinal(cifrado);
            Files.write(destino, datos);
        } catch (Exception e) {
            throw new IOException("Error descifrando archivo: " + e.getMessage(), e);
        }
    }

    // --- Autenticación de operadores -------------------------------------

    public String hashPassword(String password) {
        return passwordEncoder.encode(password);
    }

    public boolean verificarPassword(String password, String hashGuardado) {
        return passwordEncoder.matches(password, hashGuardado);
    }
}
