package cl.codes.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class GeocoderServiceTest {
    @TempDir
    Path tempDir;

    private HttpServer server;
    private final AtomicReference<URI> lastRequest = new AtomicReference<>();
    private final AtomicReference<Boolean> outsideCoverage = new AtomicReference<>(false);

    @BeforeEach
    void startGeoapifyStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/geocode/search", exchange -> {
            lastRequest.set(exchange.getRequestURI());
            String text = queryParameters(exchange.getRequestURI()).getOrDefault("text", "");
            boolean dehesa = text.contains("Dehesa");
            String formatted = dehesa
                    ? "Avenida La Dehesa, Lo Barnechea, Santiago, Chile"
                    : "Avenida Manuel Montt, Providencia, Santiago, Chile";
            String latitude = outsideCoverage.get() ? "-34.42" : "-33.42";
            String response = "{\"results\":[{\"lat\":" + latitude + ",\"lon\":-70.61,"
                    + "\"formatted\":\"" + formatted + "\",\"result_type\":\"street\","
                    + "\"city\":\"" + (dehesa ? "Lo Barnechea" : "Providencia") + "\"}]}";
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
    void stopGeoapifyStub() {
        server.stop(0);
    }

    @Test
    void usesGeoapifyAndConstrainsSearchToConfiguredCoverage() {
        GeocoderService service = service();

        GeocoderService.Coordinates result = service.geocode(
                "Avenida Manuel Montt #1234",
                "Choque ubicado en Avenida Manuel Montt 1234"
        );

        assertTrue(result.isPresent());
        assertEquals("Avenida Manuel Montt, Providencia, Santiago, Chile", result.formattedAddress());
        Map<String, String> parameters = queryParameters(lastRequest.get());
        assertEquals("test-key", parameters.get("apiKey"));
        assertTrue(parameters.get("text").contains("Chile"));
        assertTrue(parameters.get("filter").startsWith("circle:"));
        assertTrue(parameters.get("bias").startsWith("proximity:"));
    }

    @Test
    void includesDetectedCommuneInGeoapifyQuery() {
        GeocoderService service = service();

        GeocoderService.Coordinates result = service.geocode(
                "Avenida La Dehesa #123",
                "Accidente en la comuna de Lo Barnechea"
        );

        assertTrue(result.isPresent());
        assertTrue(queryParameters(lastRequest.get()).get("text").contains("Lo Barnechea"));
    }

    @Test
    void doesNotContactProviderWhenGeoapifyKeyIsMissing() {
        GeocoderService service = new GeocoderService(
                tempDir.resolve("geocache.json").toString(),
                "Chile",
                "CODES-test/1.0",
                "",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/geocode/search",
                true,
                -33.4489,
                -70.6693,
                30
        );

        GeocoderService.Coordinates result = service.geocode("Avenida Manuel Montt #1234");

        assertFalse(result.isPresent());
        assertNull(lastRequest.get());
    }

    @Test
    void doesNotContactProviderWhenExternalServicesAreDisabled() {
        GeocoderService service = new GeocoderService(
                tempDir.resolve("geocache.json").toString(),
                "Chile",
                "CODES-test/1.0",
                "test-key",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/geocode/search",
                false,
                -33.4489,
                -70.6693,
                30
        );

        GeocoderService.Coordinates result = service.geocode("Avenida Manuel Montt #1234");

        assertFalse(result.isPresent());
        assertNull(lastRequest.get());
    }

    @Test
    void rejectsResultsOutsideConfiguredCoverage() {
        outsideCoverage.set(true);
        GeocoderService service = service();

        GeocoderService.Coordinates result = service.geocode("Avenida Manuel Montt #1234");

        assertFalse(result.isPresent());
    }

    private GeocoderService service() {
        return new GeocoderService(
                tempDir.resolve("geocache.json").toString(),
                "Chile",
                "CODES-test/1.0",
                "test-key",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/geocode/search",
                true,
                -33.4489,
                -70.6693,
                30
        );
    }

    private Map<String, String> queryParameters(URI uri) {
        return Arrays.stream(uri.getRawQuery().split("&"))
                .map(parameter -> parameter.split("=", 2))
                .collect(Collectors.toMap(
                        pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair -> pair.length == 2
                                ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8)
                                : ""
                ));
    }
}
