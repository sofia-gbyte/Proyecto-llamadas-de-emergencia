package cl.codes.security;

import cl.codes.repository.CaptchaChallengeUseRepository;
import org.altcha.altcha.v2.Altcha;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@DataJpaTest
class AltchaServiceTest {

    private static final String SECRET = "a".repeat(32);
    private final String previousDataDirectory = System.getProperty("CODES_DATA_DIR");
    private final String previousAltchaSecret = System.getProperty("CODES_ALTCHA_SECRET");

    @Autowired
    private CaptchaChallengeUseRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @TempDir
    Path tempDir;

    private AltchaService service;

    @BeforeEach
    void setUp() {
        service = new AltchaService(SECRET, true, repository, jdbcTemplate);
    }

    @AfterEach
    void restoreSecretProperties() {
        restoreProperty("CODES_DATA_DIR", previousDataDirectory);
        restoreProperty("CODES_ALTCHA_SECRET", previousAltchaSecret);
    }

    @Test
    void disabledAltchaDoesNotRequireAToken() {
        AltchaService disabledService = new AltchaService(SECRET, false, repository, jdbcTemplate);

        assertTrue(disabledService.verify(null, "login"));
    }

    @Test
    void generatesSignedArgon2idChallengesWithPurposeAndExpiration() {
        Altcha.Challenge challenge = service.createChallenge("login");

        assertEquals("ARGON2ID", challenge.parameters().algorithm());
        assertEquals(8_192, challenge.parameters().memoryCost());
        assertEquals(1, challenge.parameters().cost());
        assertEquals("login", challenge.parameters().data().get("purpose"));
        assertNotNull(challenge.signature());
        assertNotNull(challenge.parameters().expiresAt());
    }

    @Test
    void verifiesValidSolutionOnlyOnceAndBindsItToItsPurpose() throws Exception {
        String payload = createValidPayload("login");

        assertFalse(service.verify(payload, "register"));
        assertTrue(service.verify(payload, "login"));

        AltchaService newServiceInstance = new AltchaService(SECRET, true, repository, jdbcTemplate);
        assertFalse(newServiceInstance.verify(payload, "login"));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void verifiesValidSolutionWithoutAnAmbientTransaction() throws Exception {
        assertTrue(service.verify(createValidPayload("login"), "login"));
    }

    @Test
    void rejectsMissingTamperedAndOversizedPayloads() {
        assertFalse(service.verify(null, "login"));
        assertFalse(service.verify("not-base64", "login"));
        assertFalse(service.verify("x".repeat(4097), "login"));
        assertFalse(service.verify("not-a-valid-purpose", "unknown"));
    }

    @Test
    void persistsGeneratedSecretForVerificationAfterRestart() throws Exception {
        assumeTrue(System.getenv("CODES_ALTCHA_SECRET") == null,
                "The test should not override an externally configured ALTCHA key.");
        System.setProperty("CODES_DATA_DIR", tempDir.toString());
        System.clearProperty("CODES_ALTCHA_SECRET");

        AltchaService firstInstance = new AltchaService("", true, repository, jdbcTemplate);
        String payload = createValidPayload(firstInstance.createChallenge("login"));
        AltchaService restartedInstance = new AltchaService("", true, repository, jdbcTemplate);

        assertTrue(Files.readString(tempDir.resolve(".codes-secrets.ps1"))
                .contains("$env:CODES_ALTCHA_SECRET = '"));
        assertTrue(restartedInstance.verify(payload, "login"));
    }

    private static String createValidPayload(String purpose) throws Exception {
        Altcha.Challenge challenge = Altcha.createChallenge(new Altcha.CreateChallengeOptions()
                .algorithm("ARGON2ID")
                .cost(1)
                .memoryCost(8_192)
                .parallelism(1)
                .keyLength(32)
                .keyPrefix("00")
                .keyPrefixLength(32)
                .counter(0)
                .hmacSignatureSecret(SECRET)
                .expiresInSeconds(300)
                .data(Map.of("purpose", purpose))
                .deriveKey(AltchaService.ARGON2ID_KDF));
        return createValidPayload(challenge);
    }

    private static String createValidPayload(Altcha.Challenge challenge) throws Exception {
        Altcha.Solution solution = Altcha.solveChallenge(
                challenge,
                AltchaService.ARGON2ID_KDF,
                0,
                1,
                Duration.ofSeconds(10)
        );
        if (solution == null) {
            throw new AssertionError("The deterministic test challenge should be solvable immediately.");
        }
        String json = """
                {"challenge":%s,"solution":{"counter":%d,"derivedKey":"%s","time":%s}}
                """.formatted(
                challenge.toJson(),
                solution.counter(),
                solution.derivedKey(),
                solution.time() == null ? "0" : solution.time()
        ).trim();
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private void restoreProperty(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
