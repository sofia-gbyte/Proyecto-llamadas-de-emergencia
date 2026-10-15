package cl.codes.controller;

import cl.codes.service.PageWatchdogService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sin autenticación a propósito (ver SecurityConfig y JwtAuthFilter): la
 * pantalla de login también cuenta como "la página sigue abierta", y que
 * expire el JWT de una operadora no debería apagar CODES para todo el
 * puesto. Ver PageWatchdogService para la lógica de apagado.
 */
@RestController
public class HeartbeatController {

    private final PageWatchdogService watchdog;

    public HeartbeatController(PageWatchdogService watchdog) {
        this.watchdog = watchdog;
    }

    @PostMapping("/api/heartbeat")
    public void heartbeat() {
        watchdog.registrarLatido();
    }
}
