package cl.codes.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MobileTlsIdentityTest {
    @TempDir
    Path tempDirectory;

    @Test
    void persistsCertificateAndReturnsStableSha256Fingerprint() {
        Path keyStore = tempDirectory.resolve("mobile.p12");

        MobileTlsIdentity first = MobileTlsIdentity.loadOrCreate(keyStore, "test-password");
        MobileTlsIdentity reloaded = MobileTlsIdentity.loadOrCreate(keyStore, "test-password");

        assertTrue(java.nio.file.Files.exists(keyStore));
        assertEquals(first.fingerprint(), reloaded.fingerprint());
        assertEquals(95, first.fingerprint().length());
    }

    @Test
    void rejectsIncorrectKeystorePassword() {
        Path keyStore = tempDirectory.resolve("mobile.p12");
        MobileTlsIdentity.loadOrCreate(keyStore, "test-password");

        assertThrows(IllegalStateException.class,
                () -> MobileTlsIdentity.loadOrCreate(keyStore, "incorrect-password"));
    }
}
