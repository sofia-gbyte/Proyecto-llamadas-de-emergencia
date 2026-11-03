package cl.codes.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalSecretBootstrapTest {
    @TempDir
    Path tempDir;

    private final String previousDataDirectory = System.getProperty("CODES_DATA_DIR");
    private final String previousTestSecret = System.getProperty("CODES_TEST_SECRET");
    private final String previousAltchaSecret = System.getProperty("CODES_ALTCHA_SECRET");

    @AfterEach
    void restoreProperties() {
        restoreProperty("CODES_DATA_DIR", previousDataDirectory);
        restoreProperty("CODES_TEST_SECRET", previousTestSecret);
        restoreProperty("CODES_ALTCHA_SECRET", previousAltchaSecret);
    }

    @Test
    void persistsGeneratedSecretsAndReusesThemWithoutRunningGeneratorAgain() {
        System.setProperty("CODES_DATA_DIR", tempDir.resolve("data").toString());
        System.clearProperty("CODES_TEST_SECRET");

        assertEquals("persistent-value", LocalSecretBootstrap.resolveOrGenerate(
                "CODES_TEST_SECRET", "", () -> "persistent-value"));
        assertEquals("persistent-value", LocalSecretBootstrap.resolveOrGenerate(
                "CODES_TEST_SECRET", "", () -> {
                    throw new AssertionError("A persisted secret must be reused.");
                }));

        assertEquals("persistent-value", System.getProperty("CODES_TEST_SECRET") == null
                ? readPersistedSecret()
                : System.getProperty("CODES_TEST_SECRET"));
        assertEquals(true, Files.exists(tempDir.resolve("data").resolve(".codes-secrets.ps1")));
    }

    @Test
    void safelyReadsLegacyPowerShellSecretAssignments() throws Exception {
        Path dataDirectory = tempDir.resolve("data");
        Files.createDirectories(dataDirectory);
        Files.writeString(dataDirectory.resolve(".codes-secrets.ps1"),
                "$env:CODES_TEST_SECRET = 'legacy-value'\n");
        System.setProperty("CODES_DATA_DIR", dataDirectory.toString());
        System.clearProperty("CODES_TEST_SECRET");

        assertEquals("legacy-value", LocalSecretBootstrap.resolveOrGenerate(
                "CODES_TEST_SECRET", "", () -> {
                    throw new AssertionError("The legacy secret must be reused.");
                }));
    }

    @Test
    void loadsQuotedSecretsWithoutExecutingPowerShell() throws Exception {
        Assumptions.assumeTrue(System.getenv("CODES_ALTCHA_SECRET") == null,
                "The test should not override an externally configured ALTCHA key.");
        Path dataDirectory = tempDir.resolve("data");
        Files.createDirectories(dataDirectory);
        Files.writeString(dataDirectory.resolve(".codes-secrets.ps1"),
                "\uFEFF$env:CODES_ALTCHA_SECRET = 'loaded-value'\nInvoke-Expression 'throw \"must not run\"'\n");
        System.setProperty("CODES_DATA_DIR", dataDirectory.toString());
        System.clearProperty("CODES_ALTCHA_SECRET");

        LocalSecretBootstrap.loadLocalSecrets();

        assertEquals("loaded-value", System.getProperty("CODES_ALTCHA_SECRET"));
    }

    private String readPersistedSecret() {
        try {
            return Files.readString(tempDir.resolve("data").resolve(".codes-secrets.properties"))
                    .lines()
                    .filter(line -> line.startsWith("CODES_TEST_SECRET="))
                    .map(line -> line.substring("CODES_TEST_SECRET=".length()))
                    .findFirst()
                    .orElseThrow();
        } catch (Exception e) {
            throw new AssertionError("Could not read the generated test secret.", e);
        }
    }

    private void restoreProperty(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
