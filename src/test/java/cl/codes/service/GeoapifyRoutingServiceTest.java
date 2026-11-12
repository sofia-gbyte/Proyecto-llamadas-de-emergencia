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

import static org.junit.jupiter.api.Assertions.assertEquals;

class GeoapifyRoutingServiceTest {
    private HttpServer server;
    private final AtomicReference<URI> lastRequest = new AtomicReference<>();

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/routing", exchange -> {
            lastRequest.set(exchange.getRequestURI());
            byte[] body = """
                    {"features":[{"properties":{"distance":1500,"time":240},
                    "geometry":{"type":"LineString","coordinates":[[-70.6,-33.4],[-70.7,-33.5]]}}]}
                    """.getBytes(StandardCharsets.UTF_8);
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
    void requestsRouteOnServerAndReturnsGeoJsonCoordinates() {
        GeoapifyRoutingService service = new GeoapifyRoutingService("routing-secret",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/routing", true);

        var result = service.route(-33.4, -70.6, -33.5, -70.7);

        assertEquals(1500, result.distance());
        assertEquals(240, result.duration());
        assertEquals(java.util.List.of(java.util.List.of(-70.6, -33.4),
                java.util.List.of(-70.7, -33.5)), result.coordinates());
        Map<String, String> parameters = Arrays.stream(lastRequest.get().getRawQuery().split("&"))
                .map(parameter -> parameter.split("=", 2))
                .collect(java.util.stream.Collectors.toMap(
                        pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
        assertEquals("routing-secret", parameters.get("apiKey"));
        assertEquals("drive", parameters.get("mode"));
        assertEquals("geojson", parameters.get("format"));
        assertEquals("-33.4,-70.6|-33.5,-70.7", parameters.get("waypoints"));
    }
}
