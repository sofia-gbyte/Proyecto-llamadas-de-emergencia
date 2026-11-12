package cl.codes.service;

import cl.codes.repository.PasswordResetTokenRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Limpieza periódica de tokens vencidos y llamadas con más de dos meses.
 */
@Service
public class MaintenanceService {
    private static final Logger log = LoggerFactory.getLogger(MaintenanceService.class);
    private static final int CALL_RETENTION_MONTHS = 2;

    private final PasswordResetTokenRepository tokenRepository;
    private final CallRetentionService callRetentionService;
    private final AuditLogService auditLogService;
    private final Path encryptedAudioDirectory;

    public MaintenanceService(
            PasswordResetTokenRepository tokenRepository,
            CallRetentionService callRetentionService,
            AuditLogService auditLogService,
            @Value("${app.audio-encriptado-dir}") String encryptedAudioDirectory
    ) {
        this.tokenRepository = tokenRepository;
        this.callRetentionService = callRetentionService;
        this.auditLogService = auditLogService;
        this.encryptedAudioDirectory = Path.of(encryptedAudioDirectory).toAbsolutePath().normalize();
    }

    /** Todos los días a las 04:00, hora del servidor (madrugada, tráfico mínimo). */
    @Scheduled(cron = "${app.maintenance.cron:0 0 4 * * *}")
    public void removeExpiredTokens() {
        try {
            tokenRepository.deleteByExpiresAtBefore(LocalDateTime.now());
            log.debug("Limpieza programada: tokens de recuperación vencidos eliminados");
        } catch (Exception e) {
            log.warn("Limpieza programada de tokens vencidos falló: {}", e.getMessage());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void purgeExpiredCallsOnStartup() {
        purgeExpiredCalls();
    }

    @Scheduled(cron = "${app.maintenance.cron:0 0 4 * * *}")
    public synchronized int purgeExpiredCalls() {
        LocalDateTime cutoff = LocalDateTime.now().minusMonths(CALL_RETENTION_MONTHS);
        int deletedCalls = 0;
        int audioCleanupFailures = 0;

        while (true) {
            List<CallRetentionService.ExpiredCall> batch = callRetentionService.deleteExpiredBatch(cutoff);
            if (batch.isEmpty()) {
                break;
            }

            deletedCalls += batch.size();
            for (CallRetentionService.ExpiredCall call : batch) {
                if (call.originalAudio() == null || call.originalAudio().isBlank()) {
                    continue;
                }
                try {
                    Path audio = Path.of(call.originalAudio()).toAbsolutePath().normalize();
                    if (!audio.getParent().equals(encryptedAudioDirectory)) {
                        audioCleanupFailures++;
                        log.warn("No se eliminó el audio de una llamada vencida porque está fuera de la carpeta de audios configurada. callId={}",
                                call.id());
                        continue;
                    }
                    Files.deleteIfExists(audio);
                } catch (IOException | SecurityException | InvalidPathException e) {
                    audioCleanupFailures++;
                    log.warn("No se pudo eliminar el audio cifrado de una llamada vencida. callId={}, reason={}",
                            call.id(), e.getClass().getSimpleName());
                }
            }
        }

        if (deletedCalls > 0) {
            String outcome = audioCleanupFailures == 0 ? "success" : "partial";
            auditLogService.recordEvent("system", "purge-expired-calls", "calls",
                    "created-before:" + cutoff + ";count:" + deletedCalls,
                    "localhost", outcome);
            log.info("Retención de llamadas: {} registros eliminados; fallos al limpiar audios: {}.",
                    deletedCalls, audioCleanupFailures);
        }
        return deletedCalls;
    }
}
