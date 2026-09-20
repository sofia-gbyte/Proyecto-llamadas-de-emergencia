package cl.codes.service;

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
 * AES-256-GCM encryption for stored audio files and bcrypt password hashing.
 */
@Service
public class SecurityService {

    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;

    private final String keyBase64;
    private final PasswordEncoder passwordEncoder;

    public SecurityService(
            @Value("${app.encrypt-key:}") String keyBase64,
            PasswordEncoder passwordEncoder
    ) {
        this.keyBase64 = keyBase64;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Generates a new AES-256 key encoded in Base64.
     */
    public static String generateKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private SecretKey getKey() {
        if (keyBase64 == null || keyBase64.isBlank()) {
            throw new IllegalStateException(
                "The CODES_ENCRYPT_KEY environment variable is missing. Generate it once with SecurityService.generateKey() and export it before starting the service.");
        }
        byte[] bytes = Base64.getDecoder().decode(keyBase64);
        return new SecretKeySpec(bytes, "AES");
    }

    public void encryptFile(Path source, Path destination) throws IOException {
        try {
            byte[] data = Files.readAllBytes(source);
            byte[] iv = new byte[GCM_IV_BYTES];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(data);

            ByteBuffer buffer = ByteBuffer.allocate(iv.length + encrypted.length);
            buffer.put(iv).put(encrypted);
            Files.write(destination, buffer.array());
            Files.delete(source);
        } catch (Exception e) {
            throw new IOException("Error encrypting file: " + e.getMessage(), e);
        }
    }

    public void decryptFile(Path source, Path destination) throws IOException {
        try {
            byte[] content = Files.readAllBytes(source);
            byte[] iv = new byte[GCM_IV_BYTES];
            System.arraycopy(content, 0, iv, 0, GCM_IV_BYTES);
            byte[] encrypted = new byte[content.length - GCM_IV_BYTES];
            System.arraycopy(content, GCM_IV_BYTES, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] data = cipher.doFinal(encrypted);
            Files.write(destination, data);
        } catch (Exception e) {
            throw new IOException("Error decrypting file: " + e.getMessage(), e);
        }
    }

    public String hashPassword(String password) {
        return passwordEncoder.encode(password);
    }

    public boolean verifyPassword(String password, String savedHash) {
        return passwordEncoder.matches(password, savedHash);
    }
}


