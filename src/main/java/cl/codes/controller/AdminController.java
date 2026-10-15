package cl.codes.controller;

import cl.codes.controller.dto.PreviewSessionResponse;
import cl.codes.model.User;
import cl.codes.repository.CallRepository;
import cl.codes.repository.UserRepository;
import cl.codes.security.JwtService;
import cl.codes.service.AuditLogService;
import cl.codes.service.SecurityService;
import cl.codes.service.SelfTestService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    // Únicas instituciones reconocidas por el clasificador y los formularios
    // de registro/creación de usuarios (ver CreateUserRequest/RegisterRequest).
    private static final List<String> INSTITUCIONES_VALIDAS = List.of("bomberos", "carabineros", "samu");

    // Corta a propósito: es solo para mirar la pantalla, no para un turno.
    private static final long PREVIEW_EXPIRACION_MINUTOS = 20;

    private final SelfTestService selfTestService;
    private final UserRepository userRepository;
    private final CallRepository callRepository;
    private final JwtService jwtService;
    private final SecurityService securityService;
    private final AuditLogService auditLogService;

    public AdminController(
            SelfTestService selfTestService,
            UserRepository userRepository,
            CallRepository callRepository,
            JwtService jwtService,
            SecurityService securityService,
            AuditLogService auditLogService
    ) {
        this.selfTestService = selfTestService;
        this.userRepository = userRepository;
        this.callRepository = callRepository;
        this.jwtService = jwtService;
        this.securityService = securityService;
        this.auditLogService = auditLogService;
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

    /**
     * Abre una sesión de "vista previa": un JWT de corta duración con rol
     * operator para una cuenta sintética ("preview_&lt;institución&gt;"),
     * de solo lectura, que le permite a un ADMINISTRATOR ver la interfaz
     * de operador de una institución sin crear ni usar credenciales reales.
     *
     * La cuenta sintética se crea la primera vez que se pide (idempotente):
     * queda activa (para que el filtro JWT la acepte) pero con una
     * contraseña aleatoria que nunca se entrega a nadie, así que nunca se
     * puede iniciar sesión con ella por /api/auth/login. CallService y
     * LiveCallService le rechazan cualquier asignación/cierre/creación de
     * llamadas por ser testAccount: solo puede consultar colas y métricas.
     */
    @PostMapping("/preview-session/{institution}")
    public ResponseEntity<?> abrirSesionDePrueba(
            @PathVariable String institution,
            Authentication auth,
            HttpServletRequest request
    ) {
        String institucionNormalizada = institution == null ? "" : institution.strip().toLowerCase(Locale.ROOT);
        if (!INSTITUCIONES_VALIDAS.contains(institucionNormalizada)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Institución no válida. Debe ser una de: " + INSTITUCIONES_VALIDAS));
        }

        String previewUsername = "preview_" + institucionNormalizada;
        User previewUser = userRepository.findByUsername(previewUsername).orElseGet(() -> {
            User nuevo = new User();
            nuevo.setUsername(previewUsername);
            // Contraseña aleatoria que nadie conoce: esta cuenta nunca debe
            // poder hacer login normal por /api/auth/login, solo recibir
            // tokens emitidos acá.
            nuevo.setPasswordHash(securityService.hashPassword(claveAleatoriaInutilizable()));
            nuevo.setRole("operator");
            nuevo.setInstitution(institucionNormalizada);
            nuevo.setActive(true);
            nuevo.setTestAccount(true);
            nuevo.setFirstName("Vista previa");
            nuevo.setLastName(institucionNormalizada);
            return userRepository.save(nuevo);
        });

        if (!previewUser.isTestAccount()) {
            // Ya existía un usuario real con ese nombre (no debería pasar,
            // pero por seguridad no reutilizamos una cuenta que no sea
            // sintética).
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "No se pudo crear la cuenta de vista previa para " + institucionNormalizada + "."));
        }
        if (!previewUser.isActive()) {
            previewUser.setActive(true);
            userRepository.save(previewUser);
        }

        String token = jwtService.generateToken(previewUsername, "operator", PREVIEW_EXPIRACION_MINUTOS);
        auditLogService.registrar(auth.getName(), "start-preview-session", "institution", institucionNormalizada, request.getRemoteAddr(), "success");

        return ResponseEntity.ok(new PreviewSessionResponse(
                token, previewUsername, institucionNormalizada, "operator", PREVIEW_EXPIRACION_MINUTOS, true
        ));
    }

    private String claveAleatoriaInutilizable() {
        String alfabeto = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789!@#$%^&*";
        SecureRandom random = new SecureRandom();
        StringBuilder clave = new StringBuilder(40);
        for (int i = 0; i < 40; i++) clave.append(alfabeto.charAt(random.nextInt(alfabeto.length())));
        return clave.toString();
    }
}
