package cl.codes.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Restricts persistent local data to its owning user on file systems that
 * expose POSIX permissions or ACLs. Unsupported file systems retain their
 * operating-system defaults.
 */
public final class LocalFilePermissions {
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private LocalFilePermissions() {
    }

    public static void restrict(Path path, boolean directory) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("Symbolic links are not permitted for private local data.");
        }

        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posix != null) {
            Files.setPosixFilePermissions(path, directory ? DIRECTORY_PERMISSIONS : FILE_PERMISSIONS);
            return;
        }

        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (acl != null) {
            UserPrincipal owner = Files.getOwner(path);
            AclEntry.Builder entry = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if (directory) {
                entry.setFlags(EnumSet.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT));
            }
            acl.setAcl(List.of(entry.build()));
        }
    }
}
