package cl.codes.controller;

import cl.codes.controller.dto.CloseRequest;
import cl.codes.controller.dto.CallResponse;
import cl.codes.controller.dto.CreateLiveCallRequest;
import cl.codes.service.AuditLogService;
import cl.codes.service.LiveCallService;
import cl.codes.service.CallService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class CallController {
    private static final String DEFAULT_MAP_TILES_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";

    private final CallService service;
    private final LiveCallService enVivoService;
    private final AuditLogService auditLogService;
    private final String mapTilesUrl;
    private final String routingUrl;
    private final String asrWebsocketUrl;

    public CallController(
            CallService service,
            LiveCallService enVivoService,
            AuditLogService auditLogService,
            @Value("${app.map.tiles-url:}") String mapTilesUrl,
            @Value("${app.map.routing-url:https://router.project-osrm.org}") String routingUrl,
            @Value("${app.asr.websocket-url:ws://localhost:6006}") String asrWebsocketUrl
    ) {
        this.service = service;
        this.enVivoService = enVivoService;
        this.auditLogService = auditLogService;
        this.mapTilesUrl = mapTilesUrl.isBlank() ? DEFAULT_MAP_TILES_URL : mapTilesUrl;
        this.routingUrl = routingUrl;
        this.asrWebsocketUrl = asrWebsocketUrl;
    }

    @GetMapping("/llamadas/pending")
    public List<CallResponse> pending(Authentication auth) {
        return service.getPending(auth).stream().map(CallResponse::de).toList();
    }

    @GetMapping("/llamadas/in-progress")
    public List<CallResponse> inProgress(Authentication auth) {
        return service.getInProgress(auth).stream().map(CallResponse::de).toList();
    }

    @GetMapping("/llamadas/closed")
    public List<CallResponse> closed(@RequestParam(defaultValue = "50") int limit, Authentication auth) {
        return service.getClosed(Math.max(1, Math.min(limit, 100)), auth).stream().map(CallResponse::de).toList();
    }

    @PostMapping("/llamadas/{id}/assign")
    @PreAuthorize("hasAnyRole('OPERATOR', 'SUPERVISOR', 'ADMINISTRATOR')")
    public ResponseEntity<?> assign(@PathVariable Long id, Authentication auth, HttpServletRequest request) {
        var call = service.assign(id, auth);
        auditLogService.registrar(auth.getName(), "assign-call", "call", String.valueOf(id), request.getRemoteAddr(), "success");
        return ResponseEntity.ok(Map.of("message", "Call " + id + " assigned to " + call.getAssignedOperator()));
    }

    @PostMapping("/llamadas/{id}/close")
    @PreAuthorize("hasAnyRole('OPERATOR', 'SUPERVISOR', 'ADMINISTRATOR')")
    public ResponseEntity<?> close(@PathVariable Long id, @Valid @RequestBody CloseRequest req, Authentication auth, HttpServletRequest request) {
        service.close(id, req.comment(), auth);
        auditLogService.registrar(auth.getName(), "close-call", "call", String.valueOf(id), request.getRemoteAddr(), "success");
        return ResponseEntity.ok(Map.of("message", "Call " + id + " closed"));
    }

    @PostMapping(value = "/llamadas/live", consumes = "multipart/form-data")
    @PreAuthorize("hasAnyRole('OPERATOR', 'SUPERVISOR', 'ADMINISTRATOR')")
    public ResponseEntity<CallResponse> createLive(
            @Valid @RequestPart("datos") CreateLiveCallRequest datos,
            @RequestPart(value = "audio", required = false) MultipartFile audio,
            Authentication auth,
            HttpServletRequest request
    ) throws Exception {
        String ip = request.getRemoteAddr();
        var call = enVivoService.create(datos.transcription(), audio, auth.getName(), ip, datos.latitudOperador(), datos.longitudOperador());
        auditLogService.registrar(auth.getName(), "create-call", "call", String.valueOf(call.getId()), ip, "success");
        return ResponseEntity.ok(CallResponse.de(call));
    }

    @GetMapping("/metrics")
    public CallService.Metrics metrics(Authentication auth) {
        return service.metrics(auth);
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/public-config")
    public Map<String, String> publicConfig() {
        return Map.of(
                "mapTilesUrl", mapTilesUrl,
                "routingUrl", routingUrl,
                "asrWebsocketUrl", asrWebsocketUrl
        );
    }
}
