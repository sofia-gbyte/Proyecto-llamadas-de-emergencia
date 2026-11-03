package cl.codes.controller;

import cl.codes.controller.dto.CloseRequest;
import cl.codes.controller.dto.CallInformationUpdateRequest;
import cl.codes.controller.dto.CallResponse;
import cl.codes.controller.dto.CreateLiveCallRequest;
import cl.codes.controller.dto.UpdateCallRequest;
import cl.codes.service.AuditLogService;
import cl.codes.service.LiveCallService;
import cl.codes.service.CallService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Map;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api")
public class CallController {
    private final CallService service;
    private final LiveCallService liveCallService;
    private final AuditLogService auditLogService;
    private final String mapTilesUrl;
    private final String routingUrl;
    private final boolean geoapifyConfigured;
    private final boolean groqConfigured;

    public CallController(
            CallService service,
            LiveCallService liveCallService,
            AuditLogService auditLogService,
            @Value("${CODES_MAP_TILES_URL:}") String mapTilesUrl,
            @Value("${CODES_ROUTING_URL:https://router.project-osrm.org}") String routingUrl,
            @Value("${app.geoapify.api-key:}") String geoapifyApiKey,
            @Value("${app.groq.api-key:}") String groqApiKey
    ) {
        this.service = service;
        this.liveCallService = liveCallService;
        this.auditLogService = auditLogService;
        this.mapTilesUrl = mapTilesUrl.isBlank()
                ? "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
                : mapTilesUrl.strip();
        this.routingUrl = routingUrl.strip();
        this.geoapifyConfigured = !geoapifyApiKey.isBlank();
        this.groqConfigured = !groqApiKey.isBlank();
    }

    @GetMapping("/llamadas/pending")
    public List<CallResponse> pending(Authentication auth) {
        return service.getPending(auth).stream().map(CallResponse::from).toList();
    }

    @GetMapping("/llamadas/in-progress")
    public List<CallResponse> inProgress(Authentication auth) {
        return service.getInProgress(auth).stream().map(CallResponse::from).toList();
    }

    @GetMapping("/llamadas/closed")
    public List<CallResponse> closed(@RequestParam(defaultValue = "50") int limit, Authentication auth) {
        return service.getClosed(Math.max(1, Math.min(limit, 100)), auth).stream().map(CallResponse::from).toList();
    }

    @GetMapping("/llamadas/{id}")
    public CallResponse getById(@PathVariable Long id, Authentication auth) {
        return CallResponse.from(service.getById(id, auth));
    }

    @PostMapping("/llamadas/{id}/updates")
    @PreAuthorize("hasAnyRole('OPERATOR', 'SUPERVISOR', 'ADMINISTRATOR')")
    public CallResponse appendInformation(
            @PathVariable Long id,
            @Valid @RequestBody CallInformationUpdateRequest requestBody,
            Authentication auth,
            HttpServletRequest request
    ) {
        var call = service.appendInformation(id, requestBody.updateId(), requestBody.information(), auth);
        auditLogService.recordEvent(auth.getName(), "update-call-information", "call", String.valueOf(id), request.getRemoteAddr(), "success");
        return CallResponse.from(call);
    }

    @PutMapping("/llamadas/{id}")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMINISTRATOR')")
    public CallResponse update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateCallRequest requestBody,
            Authentication auth,
            HttpServletRequest request
    ) {
        var call = service.update(id, requestBody.operationalSummary(), requestBody.address(), auth);
        auditLogService.recordEvent(auth.getName(), "update-call", "call", String.valueOf(id), request.getRemoteAddr(), "success");
        return CallResponse.from(call);
    }

    @DeleteMapping("/llamadas/{id}")
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    public ResponseEntity<Void> delete(@PathVariable Long id, Authentication auth, HttpServletRequest request) {
        service.deletePending(id, auth);
        auditLogService.recordEvent(auth.getName(), "archive-call", "call", String.valueOf(id), request.getRemoteAddr(), "success");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/llamadas/{id}/assign")
    @PreAuthorize("hasAnyRole('OPERATOR', 'SUPERVISOR', 'ADMINISTRATOR')")
    public ResponseEntity<?> assign(@PathVariable Long id, Authentication auth, HttpServletRequest request) {
        var call = service.assign(id, auth);
        auditLogService.recordEvent(auth.getName(), "assign-call", "call", String.valueOf(id), request.getRemoteAddr(), "success");
        return ResponseEntity.ok(Map.of("message", "Call " + id + " assigned to " + call.getAssignedOperator()));
    }

    @PostMapping("/llamadas/{id}/close")
    @PreAuthorize("hasAnyRole('OPERATOR', 'SUPERVISOR', 'ADMINISTRATOR')")
    public ResponseEntity<?> close(@PathVariable Long id, @Valid @RequestBody CloseRequest req, Authentication auth, HttpServletRequest request) {
        service.close(id, req.comment(), auth);
        auditLogService.recordEvent(auth.getName(), "close-call", "call", String.valueOf(id), request.getRemoteAddr(), "success");
        return ResponseEntity.ok(Map.of("message", "Call " + id + " closed"));
    }

    @PostMapping(value = "/llamadas/live", consumes = "multipart/form-data")
    @PreAuthorize("hasAnyRole('OPERATOR', 'SUPERVISOR', 'ADMINISTRATOR')")
    public ResponseEntity<CallResponse> createLive(
            @Valid @RequestPart("datos") CreateLiveCallRequest requestData,
            @RequestPart(value = "audio", required = false) MultipartFile audio,
            Authentication auth,
            HttpServletRequest request
    ) throws Exception {
        if (requestData == null) {
            throw new IllegalArgumentException("La solicitud de llamada está vacía.");
        }
        String ip = request.getRemoteAddr();
        var call = liveCallService.create(requestData.transcription(), audio, auth.getName(), ip,
                requestData.operatorLatitude(), requestData.operatorLongitude());
        auditLogService.recordEvent(auth.getName(), "create-call", "call", String.valueOf(call.getId()), ip, "success");
        var location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/llamadas/{id}")
                .buildAndExpand(call.getId())
                .toUri();
        return ResponseEntity.created(location).body(CallResponse.from(call));
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
    public Map<String, Object> publicConfig() {
        return Map.of(
                "mapProvider", mapTilesUrl.isBlank() ? "Esri World Imagery" : "Configured map provider",
                "mapTilesUrl", mapTilesUrl,
                "mapConfigured", !mapTilesUrl.isBlank(),
                "geocodingConfigured", geoapifyConfigured,
                "transcriptionConfigured", groqConfigured,
                "routingUrl", routingUrl,
                "transcriptionProvider", "groq-whisper"
        );
    }
}
