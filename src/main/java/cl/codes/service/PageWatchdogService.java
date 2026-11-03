package cl.codes.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CODES corre como un servidor local para un puesto de operador. Antes,
 * cerrar solo la pestaña del navegador (sin cerrar también la ventana de
 * CODES.bat) dejaba Spring Boot escuchando indefinidamente
 * en 8000: molesto para la operadora, que cree haber "cerrado" CODES,
 * y riesgoso, porque es un sistema de llamadas de emergencia con puertos
 * abiertos sin que nadie los esté usando ni vigilando.
 *
 * script.js manda un "estoy viva" (POST /api/heartbeat) cada pocos segundos
 * mientras la página siga abierta, en cualquier pestaña, incluso antes de
 * iniciar sesión. Si dejan de llegar avisos por más de {@code timeoutMs},
 * asumimos que la página se cerró y apagamos Spring Boot; el "finally" de
 * iniciar_codes_windows.ps1 detecta esa caída y detiene Spring Boot.
 *
 * Antes del primer latido no se hace nada: si nadie ha abierto la página
 * todavía, no hay "cierre" que detectar.
 */
@Service
public class PageWatchdogService {

    private static final Logger log = LoggerFactory.getLogger(PageWatchdogService.class);

    private final ApplicationContext context;
    private final long timeoutMs;

    private final AtomicLong lastHeartbeatAt = new AtomicLong(-1);
    private final AtomicBoolean shutdownStarted = new AtomicBoolean(false);

    public PageWatchdogService(
            ApplicationContext context,
            @Value("${app.page-watchdog.timeout-ms:20000}") long timeoutMs
    ) {
        this.context = context;
        this.timeoutMs = timeoutMs;
    }

    public void recordHeartbeat() {
        lastHeartbeatAt.set(System.currentTimeMillis());
    }

    @Scheduled(fixedDelayString = "${app.page-watchdog.check-interval-ms:5000}")
    void checkWatchdog() {
        long lastHeartbeat = lastHeartbeatAt.get();
        if (lastHeartbeat < 0 || shutdownStarted.get()) {
            return; // nadie ha abierto la página todavía, o ya estamos apagando
        }
        long idleTimeMs = System.currentTimeMillis() - lastHeartbeat;
        if (idleTimeMs > timeoutMs && shutdownStarted.compareAndSet(false, true)) {
            log.warn(
                "No se recibió el 'estoy viva' de la página en {} ms; se asume que se cerró. Apagando CODES...",
                idleTimeMs
            );
            // En un hilo aparte: cerrar el contexto desde dentro de la propia
            // tarea programada que lo dispara podría bloquear el apagado.
            new Thread(() -> SpringApplication.exit(context, () -> 0), "page-watchdog-shutdown").start();
        }
    }
}
