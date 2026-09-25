package cl.codes.controller;

import cl.codes.controller.dto.CloseRequest;
import cl.codes.controller.dto.CallResponse;
import cl.codes.controller.dto.CreateLiveCallRequest;
import cl.codes.service.LiveCallService;
import cl.codes.service.CallService;
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
public class CallController {

    private final CallService service;
    private final LiveCallService enVivoService;

    public CallController(CallService service, LiveCallService enVivoService) {
        this.service = service;
        this.enVivoService = enVivoService;
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
    public ResponseEntity<?> assign(@PathVariable Long id, Authentication auth) {
        var call = service.assign(id, auth);
        return ResponseEntity.ok(Map.of("message", "Call " + id + " assigned to " + call.getAssignedOperator()));
    }

    @PostMapping("/llamadas/{id}/close")
    @PreAuthorize("hasAnyRole('OPERATOR', 'SUPERVISOR', 'ADMINISTRATOR')")
    public ResponseEntity<?> close(@PathVariable Long id, @Valid @RequestBody CloseRequest req, Authentication auth) {
        service.close(id, req.comment(), auth);
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
}


