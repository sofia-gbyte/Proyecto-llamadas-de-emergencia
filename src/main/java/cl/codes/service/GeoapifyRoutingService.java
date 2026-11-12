package cl.codes.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
public class GeoapifyRoutingService {
    private static final Logger log = LoggerFactory.getLogger(GeoapifyRoutingService.class);
    private final String apiKey;
    private final String endpoint;
    private final boolean externalServicesEnabled;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public record RouteResult(List<List<Double>> coordinates, double distance, double duration) {}

    public GeoapifyRoutingService(
            @Value("${app.geoapify.routing.api-key:}") String apiKey,
            @Value("${app.geoapify.routing.url:https://api.geoapify.com/v1/routing}") String endpoint,
            @Value("${app.external-services.enabled:false}") boolean externalServicesEnabled) {
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.endpoint = endpoint == null ? "" : endpoint.strip();
        this.externalServicesEnabled = externalServicesEnabled;
    }

    public RouteResult route(double originLatitude, double originLongitude,
                             double destinationLatitude, double destinationLongitude) {
        if (!externalServicesEnabled || apiKey.isBlank() || endpoint.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Geoapify Routing no está habilitado o configurado.");
        }

        String waypoints = originLatitude + "," + originLongitude + "|"
                + destinationLatitude + "," + destinationLongitude;
        String url = endpoint + "?waypoints=" + encode(waypoints)
                + "&mode=drive&format=geojson&apiKey=" + encode(apiKey);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Geoapify Routing respondió HTTP {}.", response.statusCode());
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Geoapify Routing no pudo calcular la ruta.");
            }

            JsonNode feature = mapper.readTree(response.body()).path("features").path(0);
            JsonNode properties = feature.path("properties");
            JsonNode points = feature.path("geometry").path("coordinates");
            double distance = properties.path("distance").asDouble(Double.NaN);
            double duration = properties.path("time").asDouble(Double.NaN);
            if (!points.isArray() || points.size() < 2 || !Double.isFinite(distance)
                    || !Double.isFinite(duration) || distance < 0 || duration < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Geoapify Routing devolvió una ruta incompleta.");
            }

            List<List<Double>> coordinates = new ArrayList<>(points.size());
            for (JsonNode point : points) {
                if (!point.isArray() || point.size() < 2) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                            "Geoapify Routing devolvió coordenadas inválidas.");
                }
                double longitude = point.get(0).asDouble(Double.NaN);
                double latitude = point.get(1).asDouble(Double.NaN);
                if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                        || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                            "Geoapify Routing devolvió coordenadas inválidas.");
                }
                coordinates.add(List.of(longitude, latitude));
            }
            return new RouteResult(List.copyOf(coordinates), distance, duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "La solicitud a Geoapify Routing fue interrumpida.");
        } catch (IOException | IllegalArgumentException exception) {
            log.warn("No se pudo procesar la respuesta de Geoapify Routing: {}",
                    exception.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Geoapify Routing devolvió una respuesta inválida.");
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
