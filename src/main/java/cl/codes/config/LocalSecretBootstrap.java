package cl.codes.config;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LocalSecretBootstrap {
    private static final Object LOCK = new Object();
    private static final String GEOCODER_RADIUS_MIGRATION = "CODES_GEOCODER_RADIUS_MIGRATION_8KM";
    private static final Set<String> ALLOWED_LOCAL_SETTINGS = Set.of(
            "CODES_JWT_SECRET", "CODES_ENCRYPT_KEY", "CODES_ADMIN_USER", "CODES_ADMIN_PASSWORD",
            "CODES_ALTCHA_SECRET", "CODES_ALTCHA_ENABLED", "CODES_DB_USERNAME", "CODES_DB_PASSWORD",
            "CODES_EXTERNAL_SERVICES_ENABLED",
            "GROQ_API_KEY", "GEOAPIFY_API_KEY", "GEOAPIFY_PLACES_API_KEY",
            "GEOAPIFY_ROUTING_API_KEY", "GEOAPIFY_MAPS_API_KEY", "CODES_SMTP_HOST", "CODES_SMTP_PORT",
            "CODES_SMTP_USERNAME", "CODES_SMTP_PASSWORD", "CODES_MAIL_FROM", "CODES_PUBLIC_BASE_URL",
            "CODES_MAP_TILES_URL", "CODES_MAP_ATTRIBUTION", "CODES_MAP_PROVIDER", "CODES_ROUTING_URL",
            "CODES_ROUTING_PROVIDER", "GROQ_BASE_URL", "GROQ_MODEL", "GROQ_LANGUAGE", "GEOAPIFY_BASE_URL",
            "CODES_GEOCODER_CENTER_LAT", "CODES_GEOCODER_CENTER_LNG", "CODES_GEOCODER_RADIUS_KM"
    );
    private static final Set<String> EDITABLE_PROVIDER_SETTINGS = Set.of(
            "CODES_EXTERNAL_SERVICES_ENABLED", "GROQ_API_KEY", "GEOAPIFY_API_KEY",
            "GEOAPIFY_PLACES_API_KEY", "GEOAPIFY_ROUTING_API_KEY", "GEOAPIFY_MAPS_API_KEY",
            "CODES_SMTP_HOST", "CODES_SMTP_PORT", "CODES_SMTP_USERNAME", "CODES_SMTP_PASSWORD",
            "CODES_MAIL_FROM", "CODES_PUBLIC_BASE_URL", "CODES_MAP_TILES_URL",
            "CODES_MAP_ATTRIBUTION", "CODES_MAP_PROVIDER", "CODES_ROUTING_URL",
            "CODES_ROUTING_PROVIDER", "GROQ_BASE_URL", "GROQ_MODEL",
            "GROQ_LANGUAGE", "GEOAPIFY_BASE_URL", "CODES_GEOCODER_CENTER_LAT",
            "CODES_GEOCODER_CENTER_LNG", "CODES_GEOCODER_RADIUS_KM"
    );

    private LocalSecretBootstrap() {
    }

    public static String resolveOrGenerate(String envVarName, String configuredValue, Supplier<String> generator) {
        synchronized (LOCK) {
            String value = configuredValue;
            if (value == null || value.isBlank()) value = System.getenv(envVarName);
            if (value == null || value.isBlank()) value = System.getProperty(envVarName);
            if (value == null || value.isBlank()) value = readPersistedValue(envVarName);
            if (value != null && !value.isBlank()) {
                return value;
            }

            String generated = generator.get();
            persistEnvValue(envVarName, generated);
            return generated;
        }
    }

    public static void loadLocalSecrets() {
        synchronized (LOCK) {
            try {
                Path dataDir = dataDirectory();
                Map<String, String> secrets = new LinkedHashMap<>();
                Path powershellFile = dataDir.resolve(".codes-secrets.ps1");
                if (Files.exists(powershellFile)) {
                    LocalFilePermissions.restrict(powershellFile, false);
                    Matcher matcher = Pattern.compile(
                            "(?m)^\\s*\\$env:([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*'((?:[^']|'')*)'\\s*$"
                    ).matcher(Files.readString(powershellFile, StandardCharsets.UTF_8).replace("\uFEFF", ""));
                    while (matcher.find()) {
                        secrets.put(matcher.group(1), matcher.group(2).replace("''", "'"));
                    }
                }
                Path propertiesFile = dataDir.resolve(".codes-secrets.properties");
                readProperties(propertiesFile)
                        .forEach((key, value) -> secrets.put(String.valueOf(key), String.valueOf(value)));
                if (Files.exists(propertiesFile)) LocalFilePermissions.restrict(propertiesFile, false);
                migrateSavedGeocoderRadius(dataDir, propertiesFile, secrets);
                secrets.forEach((name, value) -> {
                    if (ALLOWED_LOCAL_SETTINGS.contains(name)
                            && !value.isBlank()
                            && System.getenv(name) == null
                            && System.getProperty(name) == null) {
                        System.setProperty(name, value);
                    }
                });
            } catch (IOException e) {
                throw new IllegalStateException("No se pudieron cargar los secretos locales de CODES.", e);
            }
        }
    }

    private static void migrateSavedGeocoderRadius(
            Path dataDir, Path propertiesFile, Map<String, String> secrets) throws IOException {
        if (System.getenv("CODES_GEOCODER_RADIUS_KM") != null
                || System.getProperty("CODES_GEOCODER_RADIUS_KM") != null
                || "8".equals(readProperties(propertiesFile).getProperty(GEOCODER_RADIUS_MIGRATION))) {
            return;
        }
        String savedRadius = secrets.get("CODES_GEOCODER_RADIUS_KM");
        if (savedRadius == null || savedRadius.isBlank()) return;

        Properties properties = readProperties(propertiesFile);
        properties.setProperty("CODES_GEOCODER_RADIUS_KM", "8");
        properties.setProperty(GEOCODER_RADIUS_MIGRATION, "8");
        writePropertiesAtomically(propertiesFile, properties);
        LocalFilePermissions.restrict(dataDir.resolve(".codes-secrets.properties"), false);
        secrets.put("CODES_GEOCODER_RADIUS_KM", "8");
    }

    public static String getLocalSetting(String name) {
        requireEditableProviderSetting(name);
        String value = System.getenv(name);
        if (value == null || value.isBlank()) value = System.getProperty(name);
        if (value != null && !value.isBlank()) return value;
        synchronized (LOCK) {
            try {
                Path dataDir = dataDirectory();
                Path propertiesFile = dataDir.resolve(".codes-secrets.properties");
                String persisted = readProperties(propertiesFile).getProperty(name);
                if (persisted != null) return persisted;
                return readPowerShellValue(dataDir.resolve(".codes-secrets.ps1"), name);
            } catch (IOException e) {
                throw new IllegalStateException("No se pudo leer la configuración local de CODES.", e);
            }
        }
    }

    public static void saveLocalSettings(Map<String, String> settings) {
        synchronized (LOCK) {
            for (String name : settings.keySet()) requireEditableProviderSetting(name);
            try {
                Path dataDir = dataDirectory();
                Files.createDirectories(dataDir);
                LocalFilePermissions.restrict(dataDir, true);
                Path propertiesFile = dataDir.resolve(".codes-secrets.properties");
                Properties properties = readProperties(propertiesFile);
                for (Map.Entry<String, String> setting : settings.entrySet()) {
                    if (setting.getValue() == null) properties.remove(setting.getKey());
                    else properties.setProperty(setting.getKey(), setting.getValue());
                }
                writePropertiesAtomically(propertiesFile, properties);
            } catch (IOException e) {
                throw new IllegalStateException("No se pudo guardar la configuración local de CODES.", e);
            }
        }
    }

    private static void requireEditableProviderSetting(String name) {
        if (!EDITABLE_PROVIDER_SETTINGS.contains(name)) {
            throw new IllegalArgumentException("La opción no se puede editar desde la configuración local.");
        }
    }

    private static void persistEnvValue(String envVarName, String value) {
        try {
            Path dataDir = dataDirectory();
            Files.createDirectories(dataDir);
            Path propertiesFile = dataDir.resolve(".codes-secrets.properties");
            Properties properties = readProperties(propertiesFile);
            properties.setProperty(envVarName, value);
            writePropertiesAtomically(propertiesFile, properties);

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
            LocalFilePermissions.restrict(secretFile, false);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo guardar el secreto local en el directorio de datos de CODES.", e);
        }
    }

    private static String readPersistedValue(String envVarName) {
        try {
            Path dataDir = dataDirectory();
            Path propertiesFile = dataDir.resolve(".codes-secrets.properties");
            String value = readProperties(propertiesFile).getProperty(envVarName);
            if (Files.exists(propertiesFile)) LocalFilePermissions.restrict(propertiesFile, false);
            if (value != null && !value.isBlank()) return value;

            Path powershellFile = dataDir.resolve(".codes-secrets.ps1");
            value = readPowerShellValue(powershellFile, envVarName);
            if (value == null) return null;
            if (!value.isBlank()) {
                Files.createDirectories(dataDir);
                Properties properties = readProperties(propertiesFile);
                properties.setProperty(envVarName, value);
                writePropertiesAtomically(propertiesFile, properties);
                return value;
            }
            return null;
        } catch (IOException e) {
            throw new IllegalStateException("No se pudieron leer los secretos locales de CODES.", e);
        }
    }

    private static String readPowerShellValue(Path powershellFile, String envVarName) throws IOException {
        if (!Files.exists(powershellFile)) return null;
        Matcher matcher = Pattern.compile(
                "(?m)^\\s*\\$env:" + Pattern.quote(envVarName) + "\\s*=\\s*'((?:[^']|'')*)'\\s*$"
        ).matcher(Files.readString(powershellFile, StandardCharsets.UTF_8).replace("\uFEFF", ""));
        return matcher.find() ? matcher.group(1).replace("''", "'") : null;
    }

    private static Properties readProperties(Path propertiesFile) throws IOException {
        Properties properties = new Properties();
        if (Files.exists(propertiesFile)) {
            try (var reader = Files.newBufferedReader(propertiesFile, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        }
        return properties;
    }

    private static void writePropertiesAtomically(Path propertiesFile, Properties properties) throws IOException {
        Files.createDirectories(propertiesFile.getParent());
        Path temporaryFile = Files.createTempFile(propertiesFile.getParent(), ".codes-secrets-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporaryFile, StandardCharsets.UTF_8)) {
                properties.store(writer, "CODES local secrets; do not share or commit this file");
            }
            LocalFilePermissions.restrict(temporaryFile, false);
            try {
                Files.move(temporaryFile, propertiesFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporaryFile, propertiesFile, StandardCopyOption.REPLACE_EXISTING);
            }
            LocalFilePermissions.restrict(propertiesFile, false);
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    private static Path dataDirectory() {
        String configuredDirectory = System.getProperty("CODES_DATA_DIR");
        if (configuredDirectory == null || configuredDirectory.isBlank()) {
            configuredDirectory = System.getenv("CODES_DATA_DIR");
        }
        return configuredDirectory == null || configuredDirectory.isBlank()
                ? Path.of("data")
                : Path.of(configuredDirectory);
    }
}
