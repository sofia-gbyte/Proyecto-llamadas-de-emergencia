package cl.codes.service;

import cl.codes.repository.PasswordResetTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Tareas de limpieza que antes solo ocurrían "de paso" (por ejemplo, los
 * tokens de recuperación vencidos solo se borraban cuando alguien pedía
 * un reset nuevo). Sin esto, si nadie pide un reset durante semanas, la
 * tabla de tokens vencidos simplemente crece sin límite.
 *
 * Deliberadamente NO borra audios ni llamadas: eso es evidencia operativa
 * y una política de retención real depende de una decisión legal/operacional
 * que no corresponde inventar acá (ver checklist de producción). Esta clase
 * solo limpia datos técnicos que no tienen valor una vez vencidos.
 */
@Service
public class MaintenanceService {
    private static final Logger log = LoggerFactory.getLogger(MaintenanceService.class);

    private final PasswordResetTokenRepository tokenRepository;

    public MaintenanceService(PasswordResetTokenRepository tokenRepository) {
        this.tokenRepository = tokenRepository;
    }

    /** Todos los días a las 04:00, hora del servidor (madrugada, tráfico mínimo). */
    @Scheduled(cron = "${app.maintenance.cron:0 0 4 * * *}")
    public void limpiarTokensVencidos() {
        try {
            tokenRepository.deleteByExpiresAtBefore(LocalDateTime.now());
            log.debug("Limpieza programada: tokens de recuperación vencidos eliminados");
        } catch (Exception e) {
            log.warn("Limpieza programada de tokens vencidos falló: {}", e.getMessage());
        }
    }
}
