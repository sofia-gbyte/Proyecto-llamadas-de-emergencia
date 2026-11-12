package cl.codes.security;

import cl.codes.config.LocalSecretBootstrap;
import cl.codes.model.CaptchaChallengeUse;
import cl.codes.repository.CaptchaChallengeUseRepository;
import org.altcha.altcha.v2.Altcha;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;

@Service
public class AltchaService {

    private static final Logger log = LoggerFactory.getLogger(AltchaService.class);
    private static final int MAX_PAYLOAD_LENGTH = 4096;
    private static final long CHALLENGE_TTL_SECONDS = 300;
    private static final int ARGON2_TIME_COST = 1;
    private static final int ARGON2_MEMORY_KIB = 8_192;
    private static final int ARGON2_PARALLELISM = 1;
    private static final int MAX_CONCURRENT_VERIFICATIONS = 2;
    private static final Set<String> SUPPORTED_PURPOSES = Set.of("login", "register");
    static final Altcha.KeyDerivationFunction ARGON2ID_KDF = (parameters, salt, password) -> {
        int iterations = bounded(parameters.cost(), 1, ARGON2_TIME_COST);
        int memoryCost = bounded(
                parameters.memoryCost() == null ? ARGON2_MEMORY_KIB : parameters.memoryCost(),
                ARGON2_MEMORY_KIB,
                ARGON2_MEMORY_KIB
        );
        int parallelism = bounded(
                parameters.parallelism() == null ? ARGON2_PARALLELISM : parameters.parallelism(),
                1,
                ARGON2_PARALLELISM
        );
        int keyLength = bounded(parameters.keyLength(), 16, 64);
        var argon2Parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withSalt(salt)
                .withIterations(iterations)
                .withMemoryAsKB(memoryCost)
                .withParallelism(parallelism)
                .build();
        var generator = new Argon2BytesGenerator();
        generator.init(argon2Parameters);
        byte[] derivedKey = new byte[keyLength];
        generator.generateBytes(password, derivedKey);
        return new Altcha.DeriveKeyResult(derivedKey);
    };

    private final String hmacKey;
    private final boolean enabled;
    private final CaptchaChallengeUseRepository challengeUseRepository;
    private final JdbcTemplate jdbcTemplate;
    private final Semaphore verificationPermits = new Semaphore(MAX_CONCURRENT_VERIFICATIONS);

    public AltchaService(
            @Value("${app.altcha.hmac-key:}") String configuredKey,
            @Value("${app.altcha.enabled:true}") boolean enabled,
            CaptchaChallengeUseRepository challengeUseRepository,
            JdbcTemplate jdbcTemplate
    ) {
        this.enabled = enabled;
        this.challengeUseRepository = challengeUseRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.hmacKey = LocalSecretBootstrap.resolveOrGenerate(
                "CODES_ALTCHA_SECRET", configuredKey, AltchaService::generateSecret);
        if (this.hmacKey.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("CODES_ALTCHA_SECRET must contain at least 32 bytes.");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Altcha.Challenge createChallenge(String purpose) {
        if (!enabled) {
            throw new IllegalStateException("ALTCHA is disabled.");
        }
        if (!isSupportedPurpose(purpose)) {
            throw new IllegalArgumentException("Unsupported ALTCHA challenge purpose.");
        }
        try {
            return Altcha.createChallenge(new Altcha.CreateChallengeOptions()
                    .algorithm("ARGON2ID")
                    .cost(ARGON2_TIME_COST)
                    .memoryCost(ARGON2_MEMORY_KIB)
                    .parallelism(ARGON2_PARALLELISM)
                    .keyLength(32)
                    .keyPrefix("00")
                    .hmacSignatureSecret(hmacKey)
                    .expiresInSeconds(CHALLENGE_TTL_SECONDS)
                    .data(Map.of("purpose", purpose))
                    .deriveKey(ARGON2ID_KDF));
        } catch (Exception e) {
            throw new IllegalStateException("Could not create an ALTCHA challenge.", e);
        }
    }

    public boolean verify(String token, String expectedPurpose) {
        if (!enabled) {
            return true;
        }
        if (!isSupportedPurpose(expectedPurpose)
                || token == null
                || token.isBlank()
                || token.length() > MAX_PAYLOAD_LENGTH) {
            return false;
        }
        if (!verificationPermits.tryAcquire()) {
            log.debug("ALTCHA verification rejected because all verification slots are busy.");
            return false;
        }

        try {
            Altcha.Payload payload = Altcha.parsePayload(token);
            Altcha.ChallengeParameters parameters = payload.challenge().parameters();
            if (!"ARGON2ID".equals(parameters.algorithm())
                    || parameters.cost() != ARGON2_TIME_COST
                    || parameters.memoryCost() == null
                    || parameters.memoryCost() != ARGON2_MEMORY_KIB
                    || parameters.parallelism() == null
                    || parameters.parallelism() != ARGON2_PARALLELISM
                    || parameters.keyLength() != 32
                    || parameters.data() == null
                    || !expectedPurpose.equals(parameters.data().get("purpose"))
                    || parameters.nonce() == null
                    || parameters.expiresAt() == null) {
                return false;
            }
            Altcha.VerifySolutionResult result = Altcha.verifySolution(token, hmacKey, ARGON2ID_KDF);
            if (!result.verified()) {
                return false;
            }
            LocalDateTime expiresAt = LocalDateTime.ofEpochSecond(
                    parameters.expiresAt().longValue(),
                    0,
                    ZoneOffset.UTC
            );
            challengeUseRepository.deleteByExpiresAtBefore(LocalDateTime.now(ZoneOffset.UTC));
            try {
                return jdbcTemplate.update(
                        "INSERT INTO captcha_challenge_uses (nonce_hash, expires_at) VALUES (?, ?)",
                        hashNonce(parameters.nonce()),
                        expiresAt
                ) == 1;
            } catch (DuplicateKeyException e) {
                return false;
            }
        } catch (IllegalArgumentException e) {
            return false;
        } catch (Exception e) {
            log.error("ALTCHA verification failed unexpectedly.", e);
            return false;
        } finally {
            verificationPermits.release();
        }
    }

    public static boolean isSupportedPurpose(String purpose) {
        return SUPPORTED_PURPOSES.contains(purpose);
    }

    private static int bounded(int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException("ALTCHA Argon2id parameter is outside the permitted range.");
        }
        return value;
    }

    private static String hashNonce(String nonce) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(nonce.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    private static String generateSecret() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        return Base64.getEncoder().encodeToString(secret);
    }
}
