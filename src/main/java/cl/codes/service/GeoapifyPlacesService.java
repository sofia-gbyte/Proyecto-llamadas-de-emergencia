package cl.codes.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

@Service
public class GeoapifyPlacesService {
    private static final Logger log = LoggerFactory.getLogger(GeoapifyPlacesService.class);
    private final String apiKey;
    private final String endpoint;
    private final boolean externalServicesEnabled;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public GeoapifyPlacesService(
            @Value("${app.geoapify.places.api-key:}") String apiKey,
            @Value("${app.geoapify.places.url:https://api.geoapify.com/v2/places}") String endpoint,
            @Value("${app.external-services.enabled:false}") boolean externalServicesEnabled) {
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.endpoint = endpoint == null ? "" : endpoint.strip();
        this.externalServicesEnabled = externalServicesEnabled;
    }

    public Optional<GeocoderService.Coordinates> findNamedHealthcarePlace(
            String address, String context, double centerLatitude, double centerLongitude, double radiusKm) {
        return findNamedHealthcarePlace(address, context, centerLatitude, centerLongitude, radiusKm, true);
    }

    public Optional<GeocoderService.Coordinates> findNamedHealthcarePlace(
            String address, String context, double centerLatitude, double centerLongitude,
            double radiusKm, boolean restrictToCoverage) {
        if (!externalServicesEnabled || apiKey.isBlank() || endpoint.isBlank()) return Optional.empty();
        if (!Double.isFinite(centerLatitude) || !Double.isFinite(centerLongitude)
                || centerLatitude < -90 || centerLatitude > 90
                || centerLongitude < -180 || centerLongitude > 180
                || !Double.isFinite(radiusKm) || radiusKm <= 0) {
            log.warn("Geoapify Places no se consultó porque el área de cobertura está configurada inválidamente.");
            return Optional.empty();
        }

        String placeName = GeocoderService.extractPlace(context);
        if (placeName == null || placeName.isBlank()) placeName = address;
        if (placeName == null || !GeocoderService.hasReliableLocationTokens(placeName)) return Optional.empty();

        String normalized = GeocoderService.normalizeText(placeName);
        String category = healthcareCategory(normalized);
        if (category == null) return Optional.empty();

        long radiusMeters = Math.min(100_000L, Math.max(1_000L, Math.round(radiusKm * 1_000)));
        String filter = "circle:" + centerLongitude + "," + centerLatitude + "," + radiusMeters;
        String url = endpoint + "?categories=" + encode(category)
                + "&name=" + encode(placeName)
                + (restrictToCoverage
                    ? "&filter=" + encode(filter)
                    : "&bias=" + encode("proximity:" + centerLongitude + "," + centerLatitude))
                + "&limit=10&lang=es&apiKey=" + encode(apiKey);

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(12))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Geoapify Places respondió HTTP {} al buscar un lugar de salud.", response.statusCode());
                return Optional.empty();
            }

            JsonNode features = mapper.readTree(response.body()).path("features");
            if (!features.isArray()) return Optional.empty();
            GeocoderService.Coordinates bestMatch = null;
            double bestDistanceKm = Double.POSITIVE_INFINITY;
            for (JsonNode feature : features) {
                JsonNode properties = feature.path("properties");
                String name = properties.path("name").asText("");
                String formatted = properties.path("formatted").asText(name);
                if (!GeocoderService.matchesSpecificLocation(placeName, name)) continue;

                JsonNode coordinates = feature.path("geometry").path("coordinates");
                if (!coordinates.isArray() || coordinates.size() < 2) continue;
                double longitude = coordinates.get(0).asDouble(Double.NaN);
                double latitude = coordinates.get(1).asDouble(Double.NaN);
                if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                        || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) continue;
                double distanceKm = GeocoderService.distanceKilometers(
                        centerLatitude, centerLongitude, latitude, longitude);
                if (!Double.isFinite(distanceKm) || restrictToCoverage && distanceKm > radiusKm) continue;

                String addressLabel = formatted.isBlank() ? name : formatted;
                if (distanceKm < bestDistanceKm) {
                    bestDistanceKm = distanceKm;
                    bestMatch = new GeocoderService.Coordinates(latitude, longitude, addressLabel);
                }
            }
            if (bestMatch != null) {
                log.info("Geoapify Places resolvió un establecimiento de salud por coincidencia de nombre.");
            }
            return Optional.ofNullable(bestMatch);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("La consulta a Geoapify Places fue interrumpida.");
            return Optional.empty();
        } catch (IOException | IllegalArgumentException exception) {
            log.warn("Geoapify Places no pudo resolver el lugar: {}",
                    exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private String healthcareCategory(String normalizedName) {
        if (normalizedName.matches(".*\\b(hospital|hospitales)\\b.*")) return "healthcare.hospital";
        if (normalizedName.matches(".*\\b(clinica|clinicas|cesfam|consultorio)\\b.*")) {
            return "healthcare.clinic_or_praxis";
        }
        return null;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
