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

    @BeforeEach
    void startNominatimStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            lastRequest.set(exchange.getRequestURI());
            String displayName = exchange.getRequestURI().getRawQuery().contains("La+Dehesa")
                    ? "Avenida La Dehesa, Lo Barnechea, Santiago, Chile"
                    : "Avenida Manuel Montt, Providencia, Santiago, Chile";
            byte[] response = ("[{\"lat\":\"-33.42\",\"lon\":\"-70.61\",\"display_name\":\""
                    + displayName
                    + "\",\"type\":\"residential\",\"address\":{\"city\":\"Providencia\"}}]")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        server.start();
    }

    @AfterEach
    void stopNominatimStub() {
        server.stop(0);
    }

    @Test
    void doesNotTreatStreetTypeAsTerritory() {
        GeocoderService service = service();

        GeocoderService.Coordinates result = service.geocode(
                "Avenida Manuel Montt #1234",
                "Choque ubicado en Avenida Manuel Montt 1234"
        );

        assertTrue(result.presente());
        assertFalse(queryParameters(lastRequest.get()).containsKey("city"));
    }

    @Test
    void usesCommuneInsteadOfCountryFromCommaSeparatedAddress() {
        GeocoderService service = service();

        GeocoderService.Coordinates result = service.geocode(
                "Calle Los Alerces #1234, Providencia, Chile"
        );

        assertTrue(result.presente());
        assertEquals("Providencia", queryParameters(lastRequest.get()).get("city"));
    }

    @Test
    void preservesMultiWordCommuneFromExplicitContext() {
        GeocoderService service = service();

        GeocoderService.Coordinates result = service.geocode(
                "Avenida La Dehesa #123",
                "Accidente en la comuna de Lo Barnechea"
        );

        assertTrue(result.presente());
        assertEquals("Lo Barnechea", queryParameters(lastRequest.get()).get("city"));
    }

    @Test
    void doesNotContactPublicProvidersWhenTheyAreNotConfigured() {
        GeocoderService service = new GeocoderService(
                tempDir.resolve("geocache.json").toString(),
                "Chile",
                "CODES-test/1.0",
                "disabled",
                "disabled",
                -33.4489,
                -70.6693,
                30
        );

        GeocoderService.Coordinates result = service.geocode("Avenida Manuel Montt #1234");

        assertFalse(result.presente());
        assertNull(lastRequest.get());
    }

    private GeocoderService service() {
        return new GeocoderService(
                tempDir.resolve("geocache.json").toString(),
                "Chile",
                "CODES-test/1.0",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "disabled",
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
