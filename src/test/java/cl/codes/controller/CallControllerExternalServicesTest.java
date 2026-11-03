package cl.codes.controller;

import cl.codes.service.AuditLogService;
import cl.codes.service.CallService;
import cl.codes.service.LiveCallService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class CallControllerExternalServicesTest {
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
        org.junit.jupiter.api.Assertions.assertFalse(config.containsValue("geo-secret"));
        org.junit.jupiter.api.Assertions.assertFalse(config.containsValue("groq-secret"));
    }
}
