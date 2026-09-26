package cl.codes.controller;

import cl.codes.repository.CallRepository;
import cl.codes.repository.UserRepository;
import cl.codes.service.SelfTestService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Panel de diagnóstico solo para administradores. Deliberadamente separado
 * de /api/health (que es público y se mantiene mínimo): acá sí se detallan
 * cosas como espacio en disco o estado del ASR, que no deberían quedar
 * visibles para cualquiera que golpee la API sin sesión.
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMINISTRATOR')")
public class AdminController {

    private final SelfTestService selfTestService;
    private final UserRepository userRepository;
    private final CallRepository callRepository;

    public AdminController(SelfTestService selfTestService, UserRepository userRepository, CallRepository callRepository) {
        this.selfTestService = selfTestService;
        this.userRepository = userRepository;
        this.callRepository = callRepository;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uptimeMinutos", ManagementFactory.getRuntimeMXBean().getUptime() / 60000);
        body.put("usuariosTotales", userRepository.count());
        body.put("llamadasTotales", callRepository.count());
        body.put("ultimoAutochequeo", selfTestService.ultimo());
        return body;
    }

    /** Fuerza un autochequeo inmediato (además del que corre solo cada cierto tiempo). */
    @PostMapping("/selftest")
    public SelfTestService.SelfTestResult selftest() {
        return selfTestService.ejecutar();
    }
}
