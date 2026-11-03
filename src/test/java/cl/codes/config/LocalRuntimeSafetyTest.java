package cl.codes.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalRuntimeSafetyTest {
    @TempDir
    Path tempDir;

    @Test
    void packagedDataDirectoryIsStableAndIndependentOfInstallationVersion() {
        Path sharedAppData = Path.of("C:\\Users\\operator\\AppData\\Local");
        Path firstInstallData = LocalDataDirectory.defaultPackagedDirectory(
                sharedAppData.toString(), Path.of("C:\\Users\\operator"));
        Path upgradedInstallData = LocalDataDirectory.defaultPackagedDirectory(
                sharedAppData.toString(), Path.of("C:\\Users\\operator"));

        assertEquals(Path.of("C:\\Users\\operator\\AppData\\Local\\CODES\\data"), firstInstallData);
        assertEquals(firstInstallData, upgradedInstallData);
    }

    @Test
    void packagedDataDirectoryHasStableHomeFallbackWhenLocalAppDataIsUnavailable() {
        Path userHome = Path.of("C:\\Users\\operator");

        assertEquals(userHome.resolve(".codes").resolve("data").toAbsolutePath().normalize(),
                LocalDataDirectory.defaultPackagedDirectory("", userHome));
    }

    @Test
    void acceptsLoopbackAddressesOnly() {
        assertTrue(LocalServerConfiguration.isLoopbackAddress("127.0.0.1"));
        assertTrue(LocalServerConfiguration.isLoopbackAddress("127.44.1.9"));
        assertTrue(LocalServerConfiguration.isLoopbackAddress("localhost"));
        assertTrue(LocalServerConfiguration.isLoopbackAddress("::1"));
        assertFalse(LocalServerConfiguration.isLoopbackAddress("0.0.0.0"));
        assertFalse(LocalServerConfiguration.isLoopbackAddress("192.168.1.10"));
        assertFalse(LocalServerConfiguration.isLoopbackAddress("example.org"));
    }

    @Test
    void preparesPersistentDataOutsideAppImageAndRetainsExistingFiles() throws IOException {
        Path appImage = Files.createDirectory(tempDir.resolve("CODES"));
        Path data = Files.createDirectory(tempDir.resolve("user-data"));
        Path existing = Files.writeString(data.resolve("existing.txt"), "keep this data");

        Path prepared = LocalDataDirectory.prepare(data, appImage);

        assertEquals(data.toRealPath(), prepared);
        assertEquals("keep this data", Files.readString(existing));
        if (Files.getFileAttributeView(existing, PosixFileAttributeView.class) != null) {
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(existing));
        }
    }

    @Test
    void rejectsDataDirectoryWithinAppImage() throws IOException {
        Path appImage = Files.createDirectory(tempDir.resolve("CODES"));
        Path packagedData = appImage.resolve("data");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> LocalDataDirectory.prepare(packagedData, appImage));

        assertTrue(exception.getMessage().contains("outside"));
        assertFalse(Files.exists(packagedData));
    }

    @Test
    void restrictsPosixDataPermissionsToOwnerWhenSupported() throws IOException {
        Path directory = Files.createDirectory(tempDir.resolve("private-data"));
        if (Files.getFileAttributeView(directory, PosixFileAttributeView.class) == null) return;

        LocalFilePermissions.restrict(directory, true);

        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE),
                Files.getPosixFilePermissions(directory));
    }
}
