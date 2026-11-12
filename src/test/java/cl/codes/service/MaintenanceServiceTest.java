package cl.codes.service;

import cl.codes.repository.PasswordResetTokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MaintenanceServiceTest {
    @TempDir
    Path tempDir;

    @Mock
    private PasswordResetTokenRepository tokenRepository;
    @Mock
    private CallRetentionService callRetentionService;
    @Mock
    private AuditLogService auditLogService;

    @Test
    void purgesExpiredCallAndItsEncryptedAudioAndAuditsTheRemoval() throws Exception {
        Path audioDirectory = Files.createDirectory(tempDir.resolve("audios_encriptados"));
        Path encryptedAudio = Files.writeString(audioDirectory.resolve("call.enc"), "encrypted-audio");
        when(callRetentionService.deleteExpiredBatch(any()))
                .thenReturn(
                        List.of(new CallRetentionService.ExpiredCall(42L, encryptedAudio.toString())),
                        List.of());
        MaintenanceService maintenance = new MaintenanceService(
                tokenRepository, callRetentionService, auditLogService, audioDirectory.toString());

        int deletedCount = maintenance.purgeExpiredCalls();

        assertEquals(1, deletedCount);
        assertFalse(Files.exists(encryptedAudio));
        verify(auditLogService).recordEvent(
                eq("system"), eq("purge-expired-calls"), eq("calls"),
                contains("count:1"), eq("localhost"), eq("success"));
    }

    @Test
    void doesNotDeleteAudioOutsideTheConfiguredEncryptedFolder() throws Exception {
        Path audioDirectory = Files.createDirectory(tempDir.resolve("audios_encriptados"));
        Path unrelatedAudio = Files.writeString(tempDir.resolve("keep.enc"), "do-not-delete");
        when(callRetentionService.deleteExpiredBatch(any()))
                .thenReturn(
                        List.of(new CallRetentionService.ExpiredCall(43L, unrelatedAudio.toString())),
                        List.of());
        MaintenanceService maintenance = new MaintenanceService(
                tokenRepository, callRetentionService, auditLogService, audioDirectory.toString());

        assertEquals(1, maintenance.purgeExpiredCalls());

        assertEquals("do-not-delete", Files.readString(unrelatedAudio));
        verify(auditLogService).recordEvent(
                eq("system"), eq("purge-expired-calls"), eq("calls"),
                contains("count:1"), eq("localhost"), eq("partial"));
    }
}
