package cl.codes.security;

import cl.codes.config.LocalSecretBootstrap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

@Service
public class MobileTotpService {
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int SECRET_BYTES = 20;
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final long STEP_SECONDS = 30;
    private static final byte[] AAD = "CODES-MOBILE-TOTP-v1".getBytes(StandardCharsets.US_ASCII);

    private final byte[] encryptionKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public MobileTotpService(@Value("${app.mobile.totp-encryption-key:}") String configuredKey) {
        String encodedKey = LocalSecretBootstrap.resolveOrGenerate(
                "CODES_MFA_ENCRYPT_KEY",
                configuredKey,
                MobileTotpService::generateEncryptionKey
        );
        try {
            this.encryptionKey = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("La clave local de cifrado MFA no es Base64 válida.", exception);
        }
        if (encryptionKey.length != 32) {
            throw new IllegalStateException("La clave local de cifrado MFA debe tener 32 bytes.");
        }
    }

    public static String generateEncryptionKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    public Enrollment createEnrollment(String username) {
        byte[] secretBytes = new byte[SECRET_BYTES];
        secureRandom.nextBytes(secretBytes);
        String secret = encodeBase32(secretBytes);
        String label = "CODES:" + username;
        String issuer = URLEncoder.encode("CODES", StandardCharsets.UTF_8);
        String encodedLabel = URLEncoder.encode(label, StandardCharsets.UTF_8).replace("+", "%20");
        String uri = "otpauth://totp/" + encodedLabel + "?secret=" + secret
                + "&issuer=" + issuer + "&algorithm=SHA1&digits=6&period=30";
        return new Enrollment(secret, encrypt(secretBytes), uri);
    }

    public Long matchingStep(String encryptedSecret, String code, Long lastAcceptedStep, Instant now) {
        if (encryptedSecret == null || code == null || !code.matches("[0-9]{6}")) return null;

        byte[] secret;
        try {
            secret = decrypt(encryptedSecret);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("No se pudo descifrar el secreto MFA del operador.", exception);
        }

        long currentStep = now.getEpochSecond() / STEP_SECONDS;
        long lastStep = lastAcceptedStep == null ? -1 : lastAcceptedStep;
        byte[] supplied = code.getBytes(StandardCharsets.US_ASCII);
        for (long step = currentStep - 1; step <= currentStep + 1; step++) {
            if (step <= lastStep || step < 0) continue;
            if (MessageDigest.isEqual(generateCode(secret, step).getBytes(StandardCharsets.US_ASCII), supplied)) {
                return step;
            }
        }
        return null;
    }

    static String codeForSecret(String base32Secret, Instant at) {
        return generateCode(decodeBase32(base32Secret), at.getEpochSecond() / STEP_SECONDS);
    }

    private String encrypt(byte[] secret) {
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(AAD);
            byte[] encrypted = cipher.doFinal(secret);
            ByteArrayOutputStream value = new ByteArrayOutputStream(iv.length + encrypted.length);
            value.writeBytes(iv);
            value.writeBytes(encrypted);
            return Base64.getEncoder().encodeToString(value.toByteArray());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("No se pudo proteger el secreto MFA.", exception);
        }
    }

    private byte[] decrypt(String encryptedSecret) throws GeneralSecurityException {
        byte[] value = Base64.getDecoder().decode(encryptedSecret);
        if (value.length < GCM_IV_BYTES + 16) {
            throw new IllegalArgumentException("El secreto MFA cifrado está incompleto.");
        }
        byte[] iv = java.util.Arrays.copyOfRange(value, 0, GCM_IV_BYTES);
        byte[] ciphertext = java.util.Arrays.copyOfRange(value, GCM_IV_BYTES, value.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, iv));
        cipher.updateAAD(AAD);
        return cipher.doFinal(ciphertext);
    }

    private static String generateCode(byte[] secret, long step) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] digest = mac.doFinal(java.nio.ByteBuffer.allocate(Long.BYTES).putLong(step).array());
            int offset = digest[digest.length - 1] & 0x0f;
            int binary = ((digest[offset] & 0x7f) << 24)
                    | ((digest[offset + 1] & 0xff) << 16)
                    | ((digest[offset + 2] & 0xff) << 8)
                    | (digest[offset + 3] & 0xff);
            return String.format(java.util.Locale.ROOT, "%06d", binary % 1_000_000);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("El algoritmo TOTP no está disponible.", exception);
        }
    }

    private static String encodeBase32(byte[] bytes) {
        StringBuilder encoded = new StringBuilder((bytes.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte value : bytes) {
            buffer = (buffer << 8) | (value & 0xff);
            bits += 8;
            while (bits >= 5) {
                encoded.append(BASE32_ALPHABET.charAt((buffer >>> (bits - 5)) & 0x1f));
                bits -= 5;
            }
        }
        if (bits > 0) encoded.append(BASE32_ALPHABET.charAt((buffer << (5 - bits)) & 0x1f));
        return encoded.toString();
    }

    private static byte[] decodeBase32(String encoded) {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char character : encoded.toUpperCase(java.util.Locale.ROOT).toCharArray()) {
            int value = BASE32_ALPHABET.indexOf(character);
            if (value < 0) throw new IllegalArgumentException("El secreto TOTP no es Base32 válido.");
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                decoded.write((buffer >>> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return decoded.toByteArray();
    }

    public record Enrollment(String secret, String encryptedSecret, String otpauthUri) {}
}
