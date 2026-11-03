package cl.codes.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalSecretBootstrapTest {
    @TempDir
    Path tempDir;

    private final String previousDataDirectory = System.getProperty("CODES_DATA_DIR");
    private final String previousTestSecret = System.getProperty("CODES_TEST_SECRET");
    private final String previousAltchaSecret = System.getProperty("CODES_ALTCHA_SECRET");
    private final String previousExternalServices = System.getProperty("CODES_EXTERNAL_SERVICES_ENABLED");
    private final String previousMapTilesUrl = System.getProperty("CODES_MAP_TILES_URL");
    private final String previousGeoapifyKey = System.getProperty("GEOAPIFY_API_KEY");

    @AfterEach
    void restoreProperties() {
        restoreProperty("CODES_DATA_DIR", previousDataDirectory);
        restoreProperty("CODES_TEST_SECRET", previousTestSecret);
        restoreProperty("CODES_ALTCHA_SECRET", previousAltchaSecret);
        restoreProperty("CODES_EXTERNAL_SERVICES_ENABLED", previousExternalServices);
        restoreProperty("CODES_MAP_TILES_URL", previousMapTilesUrl);
        restoreProperty("GEOAPIFY_API_KEY", previousGeoapifyKey);
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

    @Test
    void loadsExplicitExternalProviderOptInFromLocalSettings() throws Exception {
        Assumptions.assumeTrue(System.getenv("CODES_EXTERNAL_SERVICES_ENABLED") == null,
                "The test should not override an externally configured provider policy.");
        Path dataDirectory = tempDir.resolve("data");
        Files.createDirectories(dataDirectory);
        Files.writeString(dataDirectory.resolve(".codes-secrets.ps1"),
                "$env:CODES_EXTERNAL_SERVICES_ENABLED = 'true'\n");
        System.setProperty("CODES_DATA_DIR", dataDirectory.toString());
        System.clearProperty("CODES_EXTERNAL_SERVICES_ENABLED");

        LocalSecretBootstrap.loadLocalSecrets();

        assertEquals("true", System.getProperty("CODES_EXTERNAL_SERVICES_ENABLED"));
    }

    @Test
    void savesProviderSettingsToRestrictedPropertiesWithoutWritingPowerShell() throws Exception {
        Path dataDirectory = tempDir.resolve("data");
        System.setProperty("CODES_DATA_DIR", dataDirectory.toString());
        System.clearProperty("CODES_MAP_TILES_URL");
        System.clearProperty("GEOAPIFY_API_KEY");

        LocalSecretBootstrap.saveLocalSettings(Map.of(
                "CODES_EXTERNAL_SERVICES_ENABLED", "true",
                "CODES_MAP_TILES_URL", "https://tiles.example/{z}/{x}/{y}",
                "GEOAPIFY_API_KEY", "local-test-key"
        ));

        assertEquals("https://tiles.example/{z}/{x}/{y}",
                LocalSecretBootstrap.getLocalSetting("CODES_MAP_TILES_URL"));
        assertEquals("local-test-key", LocalSecretBootstrap.getLocalSetting("GEOAPIFY_API_KEY"));
        assertEquals(true, Files.exists(dataDirectory.resolve(".codes-secrets.properties")));
        assertEquals(false, Files.exists(dataDirectory.resolve(".codes-secrets.ps1")));
    }

    @Test
    void providerPropertiesOverrideLegacyPowerShellSettings() throws Exception {
        Path dataDirectory = tempDir.resolve("data");
        Files.createDirectories(dataDirectory);
        Files.writeString(dataDirectory.resolve(".codes-secrets.ps1"),
                "$env:CODES_MAP_TILES_URL = 'https://legacy.example/{z}/{x}/{y}'\n");
        Files.writeString(dataDirectory.resolve(".codes-secrets.properties"),
                "CODES_MAP_TILES_URL=https://current.example/{z}/{x}/{y}\n");
        System.setProperty("CODES_DATA_DIR", dataDirectory.toString());
        System.clearProperty("CODES_MAP_TILES_URL");

        LocalSecretBootstrap.loadLocalSecrets();

        assertEquals("https://current.example/{z}/{x}/{y}", System.getProperty("CODES_MAP_TILES_URL"));
    }

    @Test
    void providerSettingsCannotOverwriteCoreSecuritySecrets() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> LocalSecretBootstrap.saveLocalSettings(Map.of("CODES_JWT_SECRET", "not-allowed")));
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
