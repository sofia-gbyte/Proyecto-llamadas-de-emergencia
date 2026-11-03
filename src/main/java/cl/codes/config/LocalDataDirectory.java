package cl.codes.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Prepares persistent application data without replacing any existing files.
 */
public final class LocalDataDirectory {
    private LocalDataDirectory() {
    }

    public static Path prepare(Path configuredDirectory, Path appImageDirectory) {
        try {
            Path requested = configuredDirectory.toAbsolutePath().normalize();
            Path realAppImage = appImageDirectory != null && Files.exists(appImageDirectory)
                    ? appImageDirectory.toRealPath() : null;
            if (realAppImage != null) {
                if (requested.startsWith(appImageDirectory.toAbsolutePath().normalize())) {
                    throw new IllegalStateException(
                            "CODES_DATA_DIR must be outside the packaged application directory.");
                }
                Path existingAncestor = requested;
                while (existingAncestor != null && !Files.exists(existingAncestor)) {
                    existingAncestor = existingAncestor.getParent();
                }
                if (existingAncestor != null && existingAncestor.toRealPath().startsWith(realAppImage)) {
                    throw new IllegalStateException(
                            "CODES_DATA_DIR must be outside the packaged application directory.");
                }

            }
            Files.createDirectories(requested);
            Path dataDirectory = requested.toRealPath();
            if (realAppImage != null && dataDirectory.startsWith(realAppImage)) {
                throw new IllegalStateException(
                        "CODES_DATA_DIR must be outside the packaged application directory.");
            }

            try (Stream<Path> entries = Files.walk(dataDirectory)) {
                for (Path entry : entries.toList()) {
                    LocalFilePermissions.restrict(entry, Files.isDirectory(entry));
                }
            }
            Path probe = Files.createTempFile(dataDirectory, ".codes-write-check-", ".tmp");
            Files.delete(probe);
            return dataDirectory;
        } catch (IOException | SecurityException exception) {
            throw new IllegalStateException(
                    "CODES could not prepare private local storage. Check the configured directory and permissions.");
        }
    }

    public static Path defaultPackagedDirectory(String localAppData, Path userHome) {
        Path baseDirectory = localAppData == null || localAppData.isBlank()
                ? userHome.resolve(".codes")
                : Path.of(localAppData, "CODES");
        return baseDirectory.resolve("data").toAbsolutePath().normalize();
    }
}
