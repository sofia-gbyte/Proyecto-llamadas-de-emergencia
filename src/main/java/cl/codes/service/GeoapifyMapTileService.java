package cl.codes.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

@Service
public class GeoapifyMapTileService {
    private static final Logger log = LoggerFactory.getLogger(GeoapifyMapTileService.class);
    private static final Set<String> STYLES = Set.of("osm-carto", "osm-bright", "toner");
    private final String apiKey;
    private final String endpoint;
    private final boolean externalServicesEnabled;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();

    public GeoapifyMapTileService(
            @Value("${app.geoapify.maps.api-key:}") String apiKey,
            @Value("${app.geoapify.maps.base-url:https://maps.geoapify.com/v1/tile}") String endpoint,
            @Value("${app.external-services.enabled:false}") boolean externalServicesEnabled) {
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.endpoint = endpoint == null ? "" : endpoint.strip().replaceAll("/+$", "");
        this.externalServicesEnabled = externalServicesEnabled;
    }

    public byte[] getTile(String style, int zoom, int x, int y) {
        if (!externalServicesEnabled || apiKey.isBlank() || endpoint.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Geoapify Maps no está habilitado o configurado.");
        }
        if (!STYLES.contains(style) || zoom < 0 || zoom > 22) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tesela o estilo inválido.");
        }
        long tileCount = 1L << zoom;
        if (x < 0 || y < 0 || x >= tileCount || y >= tileCount) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Coordenadas de tesela inválidas.");
        }

        String url = endpoint + "/" + style + "/" + zoom + "/" + x + "/" + y
                + ".png?apiKey=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "image/png")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Geoapify Maps respondió HTTP {} al solicitar una tesela.", response.statusCode());
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Geoapify Maps no pudo cargar la tesela.");
            }
            if (response.body().length == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Geoapify Maps devolvió una tesela vacía.");
            }
            return response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "La solicitud a Geoapify Maps fue interrumpida.");
        } catch (IOException | IllegalArgumentException exception) {
            log.warn("No se pudo cargar una tesela de Geoapify Maps: {}",
                    exception.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "No se pudo cargar una tesela de Geoapify Maps.");
        }
    }
}
