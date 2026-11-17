package cl.codes.security;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MobileTotpServiceTest {
    private final MobileTotpService service = new MobileTotpService(
            Base64.getEncoder().encodeToString(new byte[32]));

    @Test
    void generatesRfc6238Sha1SixDigitCode() {
        assertEquals("287082", MobileTotpService.codeForSecret(
                "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", Instant.ofEpochSecond(59)));
    }

    @Test
    void encryptedEnrollmentSecretAcceptsCodeOnceAndRejectsReplay() {
        Instant now = Instant.now();
        MobileTotpService.Enrollment enrollment = service.createEnrollment("operador");
        String code = MobileTotpService.codeForSecret(enrollment.secret(), now);
        Long acceptedStep = service.matchingStep(enrollment.encryptedSecret(), code, null, now);

        assertEquals(now.getEpochSecond() / 30, acceptedStep);
        assertNull(service.matchingStep(enrollment.encryptedSecret(), code, acceptedStep, now));
    }
}
