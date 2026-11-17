package cl.codes.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SecureServerConfig {
    private static final String PREFERENCES = "codes-mobile-secure";
    private static final String CONFIG_KEY = "encrypted-server-config";
    private static final String KEYSTORE_ALIAS = "cl.codes.mobile.server-config";
    private static final int IV_LENGTH_BYTES = 12;

    private SecureServerConfig() {
    }

    static Values load(Context context) throws GeneralSecurityException, IOException {
        SharedPreferences currentPreferences = preferences(context);
        String encoded = currentPreferences.getString(CONFIG_KEY, null);
        if (encoded == null) {
            SharedPreferences legacyPreferences = context.getSharedPreferences("codes-mobile", Context.MODE_PRIVATE);
            String legacyAddress = legacyPreferences.getString("server-address", "");
            String legacyFingerprint = legacyPreferences.getString("server-fingerprint", "");
            if (!legacyAddress.isEmpty() || !legacyFingerprint.isEmpty()) {
                boolean completeConfiguration = !legacyAddress.isEmpty() && !legacyFingerprint.isEmpty();
                if (completeConfiguration) {
                    save(context, legacyAddress, legacyFingerprint);
                }
                if (!legacyPreferences.edit().clear().commit()) {
                    throw new IOException("No se pudo borrar la configuración antigua sin cifrar.");
                }
                if (completeConfiguration) return new Values(legacyAddress, legacyFingerprint);
            }
            return new Values("", "");
        }

        byte[] ciphertext;
        try {
            ciphertext = Base64.decode(encoded, Base64.NO_WRAP);
        } catch (IllegalArgumentException exception) {
            throw new GeneralSecurityException("La configuración cifrada no se puede leer.", exception);
        }
        if (ciphertext.length <= IV_LENGTH_BYTES) {
            throw new GeneralSecurityException("La configuración cifrada está incompleta.");
        }

        ByteBuffer buffer = ByteBuffer.wrap(ciphertext);
        byte[] iv = new byte[IV_LENGTH_BYTES];
        buffer.get(iv);
        byte[] encrypted = new byte[buffer.remaining()];
        buffer.get(encrypted);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, loadOrCreateKey(), new GCMParameterSpec(128, iv));
        String plaintext = new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        int separator = plaintext.indexOf('\n');
        if (separator <= 0 || separator == plaintext.length() - 1) {
            throw new GeneralSecurityException("La configuración cifrada no tiene un formato válido.");
        }
        return new Values(plaintext.substring(0, separator), plaintext.substring(separator + 1));
    }

    static void save(Context context, String address, String fingerprint)
            throws GeneralSecurityException, IOException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateKey());
        byte[] encrypted = cipher.doFinal((address + "\n" + fingerprint).getBytes(StandardCharsets.UTF_8));
        byte[] payload = ByteBuffer.allocate(cipher.getIV().length + encrypted.length)
                .put(cipher.getIV())
                .put(encrypted)
                .array();
        boolean saved = preferences(context).edit()
                .putString(CONFIG_KEY, Base64.encodeToString(payload, Base64.NO_WRAP))
                .commit();
        Arrays.fill(encrypted, (byte) 0);
        Arrays.fill(payload, (byte) 0);
        if (!saved) throw new IOException("No se pudo guardar la configuración cifrada del servidor.");
    }

    static void clear(Context context) throws GeneralSecurityException, IOException {
        boolean removed = preferences(context).edit().remove(CONFIG_KEY).commit();
        boolean legacyRemoved = context.getSharedPreferences("codes-mobile", Context.MODE_PRIVATE)
                .edit().clear().commit();
        KeyStore keyStore = loadKeyStore();
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) keyStore.deleteEntry(KEYSTORE_ALIAS);
        if (!removed || !legacyRemoved) {
            throw new IOException("No se pudo borrar la configuración guardada del servidor.");
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static SecretKey loadOrCreateKey() throws GeneralSecurityException {
        KeyStore keyStore = loadKeyStore();
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            KeyStore.Entry entry = keyStore.getEntry(KEYSTORE_ALIAS, null);
            if (entry instanceof KeyStore.SecretKeyEntry secretKeyEntry) {
                return secretKeyEntry.getSecretKey();
            }
            throw new GeneralSecurityException("La clave del almacén seguro de Android no es válida.");
        }

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static KeyStore loadKeyStore() throws GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        try {
            keyStore.load(null);
        } catch (IOException exception) {
            throw new GeneralSecurityException("No se pudo abrir el almacén seguro de Android.", exception);
        }
        return keyStore;
    }

    static final class Values {
        final String address;
        final String fingerprint;

        private Values(String address, String fingerprint) {
            this.address = address;
            this.fingerprint = fingerprint;
        }
    }
}
