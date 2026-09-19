package cl.apppolicial.controlador;

import cl.apppolicial.controlador.dto.CerrarRequest;
import cl.apppolicial.controlador.dto.LlamadaResponse;
import cl.apppolicial.controlador.dto.CrearLlamadaEnVivoRequest;
import cl.apppolicial.servicio.LlamadaEnVivoService;
import cl.apppolicial.servicio.LlamadaService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class LlamadaController {

    private final LlamadaService service;
    private final LlamadaEnVivoService enVivoService;

    public LlamadaController(LlamadaService service, LlamadaEnVivoService enVivoService) {
        this.service = service;
        this.enVivoService = enVivoService;
    }

    @GetMapping("/llamadas/pendientes")
    public List<LlamadaResponse> pendientes() {
        return service.obtenerPendientes().stream().map(LlamadaResponse::de).toList();
    }

    @GetMapping("/llamadas/en-progreso")
    public List<LlamadaResponse> enProgreso() {
        return service.obtenerEnProgreso().stream().map(LlamadaResponse::de).toList();
    }

    @GetMapping("/llamadas/cerradas")
    public List<LlamadaResponse> cerradas(@RequestParam(defaultValue = "50") int limite) {
        return service.obtenerCerradas(Math.min(limite, 500)).stream().map(LlamadaResponse::de).toList();
    }

    // El operador que toma el caso es SIEMPRE el usuario autenticado del
    // token, nunca un nombre escrito a mano en el body — si no, cualquiera
    // podría "tomar" un caso a nombre de otro operador y falsear la
    // auditoría de quién atendió qué.
    @PostMapping("/llamadas/{id}/asignar")
    @PreAuthorize("hasAnyRole('OPERADOR', 'SUPERVISOR', 'ADMINISTRADOR')")
    public ResponseEntity<?> asignar(@PathVariable Long id, Authentication auth) {
        var l = service.asignar(id, auth.getName());
        return ResponseEntity.ok(Map.of("mensaje", "Llamada " + id + " asignada a " + l.getOperadorAsignado()));
    }

    @PostMapping("/llamadas/{id}/cerrar")
    @PreAuthorize("hasAnyRole('OPERADOR', 'SUPERVISOR', 'ADMINISTRADOR')")
    public ResponseEntity<?> cerrar(@PathVariable Long id, @Valid @RequestBody CerrarRequest req) {
        service.cerrar(id, req.comentario());
        return ResponseEntity.ok(Map.of("mensaje", "Llamada " + id + " cerrada"));
    }

    @PostMapping(value = "/llamadas/en-vivo", consumes = "multipart/form-data")
    @PreAuthorize("hasAnyRole('OPERADOR', 'SUPERVISOR', 'ADMINISTRADOR')")
    public ResponseEntity<LlamadaResponse> crearEnVivo(
            @Valid @RequestPart("datos") CrearLlamadaEnVivoRequest datos,
            @RequestPart(value = "audio", required = false) MultipartFile audio,
            Authentication auth,
            HttpServletRequest request
    ) throws Exception {
        String ip = request.getRemoteAddr();
        var llamada = enVivoService.crear(datos.transcripcion(), audio, auth.getName(), ip, datos.latitudOperador(), datos.longitudOperador());
        return ResponseEntity.ok(LlamadaResponse.de(llamada));
    }

    @GetMapping("/metricas")
    public LlamadaService.Metricas metricas() {
        return service.metricas();
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
