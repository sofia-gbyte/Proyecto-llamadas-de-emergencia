package cl.codes.controller;

import cl.codes.service.AuditLogService;
import cl.codes.service.CallService;
import cl.codes.service.LiveCallService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CallControllerExternalServicesTest {
    @Test
    void bulkArchiveReturnsCountAndAuditsTheOperationForAdministrators() throws Exception {
        CallService service = mock(CallService.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        CallController controller = new CallController(
                service,
                mock(LiveCallService.class),
                auditLogService,
                "",
                "",
                "",
                "",
                "",
                false
        );
        Authentication auth = new UsernamePasswordAuthenticationToken("admin", null);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(service.archiveAllActiveCalls(auth)).thenReturn(3);

        var response = controller.archiveAllCalls(auth, request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(3, response.getBody().get("archivedCount"));
        verify(auditLogService).recordEvent(
                "admin", "archive-all-calls", "calls", "all-active:3", "127.0.0.1", "success");
        var method = CallController.class.getMethod(
                "archiveAllCalls", Authentication.class, HttpServletRequest.class);
        assertEquals("hasRole('ADMINISTRATOR')", method.getAnnotation(PreAuthorize.class).value());
    }

    @Test
    void publicConfigurationDoesNotExposeOrEnableExternalProvidersByDefault() {
        CallController controller = new CallController(
                mock(CallService.class),
                mock(LiveCallService.class),
                mock(AuditLogService.class),
                "",
                "&copy; Example provider",
                "https://router.example.test",
                "configured-geo-key",
                "configured-groq-key",
                false
        );

        var config = controller.publicConfig();

        assertEquals(false, config.get("externalServicesEnabled"));
        assertEquals("", config.get("mapTilesUrl"));
        assertEquals("", config.get("routingUrl"));
        assertFalse((Boolean) config.get("mapConfigured"));
        assertFalse((Boolean) config.get("geocodingConfigured"));
        assertFalse((Boolean) config.get("transcriptionConfigured"));
        assertEquals(-33.4489, config.get("geocoderCenterLat"));
        assertEquals(-70.6693, config.get("geocoderCenterLng"));
        assertEquals(8.0, config.get("geocoderRadiusKm"));
    }

    @Test
    void publicConfigurationEnablesConfiguredMapRoutingAndProviderFeaturesWithoutExposingKeys() {
        CallController controller = new CallController(
                mock(CallService.class),
                mock(LiveCallService.class),
                mock(AuditLogService.class),
                "https://tiles.example/{z}/{x}/{y}",
                "&copy; Example provider",
                "https://routes.example",
                "geo-secret",
                "groq-secret",
                true
        );

        var config = controller.publicConfig();

        assertEquals(true, config.get("externalServicesEnabled"));
        assertEquals("https://tiles.example/{z}/{x}/{y}", config.get("mapTilesUrl"));
        assertEquals("https://routes.example", config.get("routingUrl"));
        assertTrue((Boolean) config.get("mapConfigured"));
        assertTrue((Boolean) config.get("geocodingConfigured"));
        assertTrue((Boolean) config.get("transcriptionConfigured"));
        assertTrue((Boolean) config.get("routingConfigured"));
        org.junit.jupiter.api.Assertions.assertFalse(config.containsValue("geo-secret"));
        org.junit.jupiter.api.Assertions.assertFalse(config.containsValue("groq-secret"));
    }

    @Test
    void proxyConfigurationKeepsGeoapifyKeysOnTheLocalServer() {
        CallController controller = new CallController(
                mock(CallService.class),
                mock(LiveCallService.class),
                mock(AuditLogService.class),
                "",
                "",
                "https://router.example.test",
                "geo-secret",
                "places-secret",
                "routing-secret",
                "maps-secret",
                "geoapify-osm-carto",
                "geoapify",
                "groq-secret",
                -33.45,
                -70.66,
                15,
                true
        );

        var config = controller.publicConfig();

        assertEquals("/api/maps/tiles/osm-carto/{z}/{x}/{y}.png", config.get("mapTilesUrl"));
        assertEquals("geoapify", config.get("routingProvider"));
        assertEquals("", config.get("routingUrl"));
        assertTrue((Boolean) config.get("routingConfigured"));
        assertTrue((Boolean) config.get("placesConfigured"));
        assertEquals(-33.45, config.get("geocoderCenterLat"));
        assertEquals(-70.66, config.get("geocoderCenterLng"));
        assertEquals(15.0, config.get("geocoderRadiusKm"));
        assertFalse(config.containsValue("geo-secret"));
        assertFalse(config.containsValue("places-secret"));
        assertFalse(config.containsValue("routing-secret"));
        assertFalse(config.containsValue("maps-secret"));
    }
}
