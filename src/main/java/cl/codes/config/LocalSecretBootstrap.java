package cl.codes.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

public final class LocalSecretBootstrap {

    private LocalSecretBootstrap() {
    }

    public static String resolveOrGenerate(String envVarName, String configuredValue, Supplier<String> generator) {
        String value = configuredValue;
        if ((value == null || value.isBlank()) && System.getenv(envVarName) != null) {
            value = System.getenv(envVarName);
        }
        if ((value == null || value.isBlank()) && System.getProperty(envVarName) != null) {
            value = System.getProperty(envVarName);
        }

        if (value != null && !value.isBlank()) {
            return value;
        }

        String generated = generator.get();
        persistEnvValue(envVarName, generated);
        return generated;
    }

    private static void persistEnvValue(String envVarName, String value) {
        try {
            Path dataDir = Path.of("data");
            Files.createDirectories(dataDir);
            Path secretFile = dataDir.resolve(".codes-secrets.ps1");

            String line = "$env:" + envVarName + " = '" + value + "'";
            String existing = Files.exists(secretFile)
                    ? Files.readString(secretFile, StandardCharsets.UTF_8)
                    : "";

            if (!existing.contains("$env:" + envVarName + " =")) {
                StringBuilder sb = new StringBuilder(existing);
                if (!existing.isBlank() && !existing.endsWith(System.lineSeparator())) {
                    sb.append(System.lineSeparator());
                }
                sb.append(line).append(System.lineSeparator());
                Files.writeString(secretFile, sb.toString(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo guardar el secreto local en data/.codes-secrets.ps1", e);
        }
    }
}
