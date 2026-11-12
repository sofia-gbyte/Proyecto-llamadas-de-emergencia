package cl.codes.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class GeoapifyPlacesServiceTest {
    private HttpServer server;
    private final AtomicReference<URI> lastRequest = new AtomicReference<>();
    private final AtomicReference<String> resultName = new AtomicReference<>("Hospital Barros Luco");
    private final AtomicReference<Double> resultLatitude = new AtomicReference<>(-33.55);

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/places", exchange -> {
            lastRequest.set(exchange.getRequestURI());
            String name = resultName.get();
            String response = "{\"features\":[{\"properties\":{\"name\":\"" + name
                    + "\",\"formatted\":\"" + name + ", San Miguel, Santiago, Chile\"},"
                    + "\"geometry\":{\"coordinates\":[-70.65," + resultLatitude.get() + "]}}]}";
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    @Test
    void resolvesOnlyMatchingNamedHospitalInsideConfiguredSearch() {
        assertEquals("Hospital Barros Luco",
                GeocoderService.extractPlace("Incendio en el Hospital Barros Luco, hay humo."));
        assertTrue(GeocoderService.hasReliableLocationTokens("Hospital Barros Luco"));
        assertTrue(GeocoderService.matchesSpecificLocation("Hospital Barros Luco", "Hospital Barros Luco"));
        GeoapifyPlacesService service = service();

        var result = service.findNamedHealthcarePlace(
                null, "Incendio en el Hospital Barros Luco, hay humo.",
                -33.4489, -70.6693, 30);

        assertTrue(result.isPresent());
        assertEquals(-33.55, result.get().lat());
        assertEquals(-70.65, result.get().lng());
        assertEquals("Hospital Barros Luco, San Miguel, Santiago, Chile", result.get().formattedAddress());
        Map<String, String> parameters = queryParameters(lastRequest.get());
        assertEquals("test-key", parameters.get("apiKey"));
        assertEquals("healthcare.hospital", parameters.get("categories"));
        assertTrue(parameters.get("filter").startsWith("circle:-70.6693,-33.4489,"));
    }

    @Test
    void refusesDifferentNamedHospitalInsteadOfChoosingTheNearestOne() {
        resultName.set("Hospital Traumatológico");

        var result = service().findNamedHealthcarePlace(
                null, "Incendio en el Hospital Barros Luco, hay humo.",
                -33.4489, -70.6693, 30);

        assertTrue(result.isEmpty());
    }

    @Test
    void refusesMatchingPlaceOutsideConfiguredCoverage() {
        var result = service().findNamedHealthcarePlace(
                null, "Incendio en el Hospital Barros Luco, hay humo.",
                -33.4489, -70.6693, 1);

        assertTrue(result.isEmpty());
    }

    @Test
    void canFindMatchingPlaceOutsideCoverageToReportWhyTheCallWasRejected() {
        var result = service().findNamedHealthcarePlace(
                null, "Incendio en el Hospital Barros Luco, hay humo.",
                -33.4489, -70.6693, 1, false);

        assertTrue(result.isPresent());
        Map<String, String> parameters = queryParameters(lastRequest.get());
        assertFalse(parameters.containsKey("filter"));
        assertEquals("proximity:-70.6693,-33.4489", parameters.get("bias"));
    }

    private GeoapifyPlacesService service() {
        return new GeoapifyPlacesService("test-key",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v2/places", true);
    }

    private Map<String, String> queryParameters(URI uri) {
        return Arrays.stream(uri.getRawQuery().split("&"))
                .map(parameter -> parameter.split("=", 2))
                .collect(java.util.stream.Collectors.toMap(
                        pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
    }
}
