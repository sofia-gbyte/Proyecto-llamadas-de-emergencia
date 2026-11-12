package cl.codes.controller;

import cl.codes.controller.dto.CloseRequest;
import cl.codes.controller.dto.CallInformationUpdateRequest;
import cl.codes.controller.dto.CallResponse;
import cl.codes.controller.dto.CreateLiveCallRequest;
import cl.codes.controller.dto.UpdateCallRequest;
import cl.codes.service.AuditLogService;
import cl.codes.service.LiveCallService;
import cl.codes.service.CallService;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final String mapAttribution;
    private final String routingUrl;
    private final String routingProvider;
    private final boolean routingConfigured;
    private final String mapProvider;
    private final String placesApiKey;
    private final boolean externalServicesEnabled;
    private final boolean geoapifyConfigured;
    private final boolean groqConfigured;
    private final double geocoderCenterLatitude;
    private final double geocoderCenterLongitude;
    private final double geocoderRadiusKm;

    @Autowired
    public CallController(
            CallService service,
            LiveCallService liveCallService,
            AuditLogService auditLogService,
            @Value("${CODES_MAP_TILES_URL:}") String mapTilesUrl,
            @Value("${CODES_MAP_ATTRIBUTION:&copy; Esri}") String mapAttribution,
            @Value("${CODES_ROUTING_URL:https://router.project-osrm.org}") String routingUrl,
            @Value("${app.geoapify.api-key:}") String geoapifyApiKey,
            @Value("${app.geoapify.places.api-key:}") String placesApiKey,
            @Value("${app.geoapify.routing.api-key:}") String geoapifyRoutingApiKey,
            @Value("${app.geoapify.maps.api-key:}") String geoapifyMapsApiKey,
            @Value("${CODES_MAP_PROVIDER:}") String configuredMapProvider,
            @Value("${CODES_ROUTING_PROVIDER:osrm}") String configuredRoutingProvider,
            @Value("${app.groq.api-key:}") String groqApiKey,
            @Value("${app.geocoder.centro-lat:-33.4489}") double geocoderCenterLatitude,
            @Value("${app.geocoder.centro-lng:-70.6693}") double geocoderCenterLongitude,
            @Value("${app.geocoder.radio-km:8}") double geocoderRadiusKm,
            @Value("${app.external-services.enabled:false}") boolean externalServicesEnabled
    ) {
        this.service = service;
        this.liveCallService = liveCallService;
        this.auditLogService = auditLogService;
        this.externalServicesEnabled = externalServicesEnabled;
        this.mapProvider = resolveMapProvider(configuredMapProvider, mapTilesUrl);
        this.mapTilesUrl = resolveMapTilesUrl(mapTilesUrl, this.mapProvider, geoapifyMapsApiKey,
                externalServicesEnabled);
        this.mapAttribution = resolveMapAttribution(mapAttribution, this.mapProvider, externalServicesEnabled);
        this.routingProvider = resolveRoutingProvider(configuredRoutingProvider);
        this.routingUrl = externalServicesEnabled && "osrm".equals(this.routingProvider)
                ? routingUrl.strip() : "";
        this.routingConfigured = externalServicesEnabled && ("geoapify".equals(this.routingProvider)
                ? geoapifyRoutingApiKey != null && !geoapifyRoutingApiKey.isBlank()
                : !this.routingUrl.isBlank());
        this.geoapifyConfigured = externalServicesEnabled && !geoapifyApiKey.isBlank();
        this.placesApiKey = placesApiKey == null ? "" : placesApiKey.strip();
        this.groqConfigured = externalServicesEnabled && !groqApiKey.isBlank();
        this.geocoderCenterLatitude = geocoderCenterLatitude;
        this.geocoderCenterLongitude = geocoderCenterLongitude;
        this.geocoderRadiusKm = geocoderRadiusKm;
    }

    public CallController(
            CallService service, LiveCallService liveCallService, AuditLogService auditLogService,
            String mapTilesUrl, String mapAttribution, String routingUrl, String geoapifyApiKey,
            String groqApiKey, boolean externalServicesEnabled) {
        this(service, liveCallService, auditLogService, mapTilesUrl, mapAttribution, routingUrl,
                geoapifyApiKey, geoapifyApiKey, "", "", mapTilesUrl == null || mapTilesUrl.isBlank()
                        ? "esri" : "custom", "osrm", groqApiKey,
                -33.4489, -70.6693, 8, externalServicesEnabled);
    }

    private String resolveMapProvider(String configuredProvider, String configuredTilesUrl) {
        if (configuredProvider == null || configuredProvider.isBlank()) {
            return configuredTilesUrl == null || configuredTilesUrl.isBlank() ? "esri" : "custom";
        }
        return switch (configuredProvider.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "geoapify-osm-carto" -> "geoapify-osm-carto";
            case "geoapify-osm-bright" -> "geoapify-osm-bright";
            case "geoapify-toner" -> "geoapify-toner";
            case "custom" -> "custom";
            default -> "esri";
        };
    }

    private String resolveMapTilesUrl(String configuredUrl, String provider, String mapsKey, boolean enabled) {
        if (!enabled) return "";
        if (provider.startsWith("geoapify-")) {
            if (mapsKey == null || mapsKey.isBlank()) return "";
            String style = provider.substring("geoapify-".length());
            return "/api/maps/tiles/" + style + "/{z}/{x}/{y}.png";
        }
        if ("custom".equals(provider)) return configuredUrl == null ? "" : configuredUrl.strip();
        return "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}";
    }

    private String resolveMapAttribution(String configuredAttribution, String provider, boolean enabled) {
        if (!enabled) return "";
        if (provider.startsWith("geoapify-")) {
            return "&copy; OpenStreetMap contributors &copy; Geoapify";
        }
        if ("custom".equals(provider)) return configuredAttribution == null ? "" : configuredAttribution.strip();
        return "&copy; Esri";
    }

    private String resolveRoutingProvider(String configuredProvider) {
        return configuredProvider != null && configuredProvider.strip().equalsIgnoreCase("geoapify")
                ? "geoapify" : "osrm";
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

    @PostMapping("/llamadas/archive-all")
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    public ResponseEntity<Map<String, Integer>> archiveAllCalls(Authentication auth, HttpServletRequest request) {
        int archivedCount = service.archiveAllActiveCalls(auth);
        auditLogService.recordEvent(auth.getName(), "archive-all-calls", "calls",
                "all-active:" + archivedCount, request.getRemoteAddr(), "success");
        return ResponseEntity.ok(Map.of("archivedCount", archivedCount));
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
        return Map.ofEntries(
                Map.entry("externalServicesEnabled", externalServicesEnabled),
                Map.entry("mapProvider", !externalServicesEnabled ? "Disabled" : mapProviderLabel()),
                Map.entry("mapTilesUrl", mapTilesUrl),
                Map.entry("mapAttribution", mapAttribution),
                Map.entry("mapConfigured", !mapTilesUrl.isBlank()),
                Map.entry("geocodingConfigured",
                        geoapifyConfigured || externalServicesEnabled && !placesApiKey.isBlank()),
                Map.entry("placesConfigured", externalServicesEnabled && !placesApiKey.isBlank()),
                Map.entry("transcriptionConfigured", groqConfigured),
                Map.entry("routingUrl", routingUrl),
                Map.entry("routingProvider", externalServicesEnabled ? routingProvider : "disabled"),
                Map.entry("routingConfigured", routingConfigured),
                Map.entry("geocoderCenterLat", geocoderCenterLatitude),
                Map.entry("geocoderCenterLng", geocoderCenterLongitude),
                Map.entry("geocoderRadiusKm", geocoderRadiusKm),
                Map.entry("transcriptionProvider", "groq-whisper")
        );
    }

    private String mapProviderLabel() {
        return switch (mapProvider) {
            case "geoapify-osm-carto" -> "Geoapify OSM Carto";
            case "geoapify-osm-bright" -> "Geoapify OSM Bright";
            case "geoapify-toner" -> "Geoapify Toner";
            case "custom" -> "Configured map provider";
            default -> "Esri World Imagery";
        };
    }
}
