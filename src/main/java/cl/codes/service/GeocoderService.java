package cl.codes.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * Geocodificación tolerante mediante Geoapify.
 *
 * Área de cobertura: TODO resultado se descarta si queda a más de
 * app.geocoder.radio-km del centro operativo configurado en
 * app.geocoder.centro-*.
 * La consulta y el filtro posterior respetan el área operativa configurada.
 *
 * La transcripción original nunca se modifica; solo se normaliza la consulta.
 */
@Service
public class GeocoderService {
    private static final Logger log = LoggerFactory.getLogger(GeocoderService.class);

    private static final double ACCEPTANCE_THRESHOLD = 0.55;

    public record Coordinates(Double lat, Double lng, String formattedAddress) {
        static final Coordinates EMPTY = new Coordinates(null, null, null);
        public Coordinates(Double lat, Double lng) {
            this(lat, lng, null);
        }
        boolean isPresent() { return lat != null && lng != null; }
    }

    public record GeocodingResult(Coordinates coordinates, boolean outsideCoverage, double radiusKm) {
    }

    private record Candidate(double lat, double lng, String displayName, String resultType, String municipality) {}

    private final Path cachePath;
    private final String country;
    private final String userAgent;
    private final String geoapifyApiKey;
    private final String geoapifyBaseUrl;
    private final GeoapifyPlacesService placesService;
    private final boolean externalServicesEnabled;
    private final double defaultCenterLatitude;
    private final double defaultCenterLongitude;
    private final double radiusKm;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    // La caché de geocodificación se mantiene en memoria y se carga una sola
    // vez al iniciar. Antes se releía y reescribía el archivo COMPLETO en
    // cada consulta de geocodificación (varias por llamada procesada); con
    // miles de direcciones cacheadas eso se vuelve un costo de disco que
    // crece con el tiempo. Ahora las lecturas son instantáneas (memoria) y
    // solo se escribe a disco cuando aparece una dirección nueva.
    private final Map<String, List<Candidate>> geocodeCache = new java.util.concurrent.ConcurrentHashMap<>();

    @PostConstruct
    void loadInitialCache() {
        geocodeCache.putAll(loadCache());
        log.info("Caché de geocodificación cargada en memoria: {} entradas", geocodeCache.size());
    }

    public GeocoderService(
            @Value("${app.geocache-path}") String geocachePath,
            @Value("${app.geocoder.pais}") String country,
            @Value("${app.geocoder.user-agent}") String userAgent,
            @Value("${app.geoapify.api-key:}") String geoapifyApiKey,
            @Value("${app.geoapify.base-url:https://api.geoapify.com/v1/geocode/search}") String geoapifyBaseUrl,
            @Value("${app.external-services.enabled:false}") boolean externalServicesEnabled,
            @Value("${app.geocoder.centro-lat:-33.4489}") double defaultCenterLatitude,
            @Value("${app.geocoder.centro-lng:-70.6693}") double defaultCenterLongitude,
            @Value("${app.geocoder.radio-km:8}") double radiusKm,
            GeoapifyPlacesService placesService
    ) {
        this.cachePath = Path.of(geocachePath);
        this.country = country;
        this.userAgent = userAgent;
        this.geoapifyApiKey = geoapifyApiKey == null ? "" : geoapifyApiKey.strip();
        this.geoapifyBaseUrl = normalizeBaseUrl(geoapifyBaseUrl);
        this.externalServicesEnabled = externalServicesEnabled;
        this.defaultCenterLatitude = defaultCenterLatitude;
        this.defaultCenterLongitude = defaultCenterLongitude;
        this.radiusKm = radiusKm;
        this.placesService = placesService;
    }

    public Coordinates geocode(String address) {
        return geocode(address, null);
    }

    public Coordinates geocode(String address, String context) {
        return geocode(address, context, null, null);
    }

    public GeocodingResult geocodeForLiveCall(
            String address, String context, Double sourceLatitude, Double sourceLongitude) {
        return geocodeInternal(address, context, sourceLatitude, sourceLongitude, true);
    }

    /**
     * Variante para llamadas en vivo. Cuando el operador habilita su ubicación,
     * esta centra el área de búsqueda sin sustituir la ubicación reportada.
     */
    public Coordinates geocode(String address, String context, Double sourceLatitude, Double sourceLongitude) {
        return geocodeInternal(address, context, sourceLatitude, sourceLongitude, false).coordinates();
    }

    private GeocodingResult geocodeInternal(
            String address, String context, Double sourceLatitude, Double sourceLongitude, boolean detectOutsideCoverage) {
        boolean outsideCoverage = false;
        if (!externalServicesEnabled) {
            log.debug("Geocoding skipped because external providers are disabled.");
            return new GeocodingResult(Coordinates.EMPTY, false, radiusKm);
        }
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        if (address != null && !address.isBlank()) queries.add(address.strip());
        if (context != null && !context.isBlank()) {
            String location = extractLocationContext(context);
            if (location != null && !location.isBlank()) queries.add(location);
            String place = extractPlace(context);
            if (place != null && !place.isBlank()) queries.add(place);
        }
        if (queries.isEmpty()) {
            log.debug("Geocodificación omitida: no hay dirección ni contexto utilizable.");
            return new GeocodingResult(Coordinates.EMPTY, false, radiusKm);
        }

        String territory = extractTerritory(context, address);

        if ((sourceLatitude == null) != (sourceLongitude == null)) {
            throw new IllegalArgumentException("Operator coordinates must be provided together");
        }
        if (sourceLatitude != null && (!Double.isFinite(sourceLatitude) || sourceLatitude < -90 || sourceLatitude > 90
                || !Double.isFinite(sourceLongitude) || sourceLongitude < -180 || sourceLongitude > 180)) {
            throw new IllegalArgumentException("Invalid operator coordinates");
        }
        double centerLat = sourceLatitude == null ? defaultCenterLatitude : sourceLatitude;
        double centerLng = sourceLongitude == null ? defaultCenterLongitude : sourceLongitude;
        boolean checkOutsideCoverage = detectOutsideCoverage && sourceLatitude != null;
        if (geoapifyApiKey.isBlank() || geoapifyBaseUrl.isBlank()) {
            log.info("Geoapify Geocoding no está configurado; se probará Places si hay una clave disponible.");
        }

        // Geoapify busca solo dentro del radio operativo y la coincidencia
        // territorial se usa tanto para construir la consulta como para puntuar.
        for (String query : queries) {
            if (!hasReliableLocationTokens(query)) {
                log.info("Geocodificación omitida: la ubicación reportada es demasiado general para ubicarla con seguridad.");
                continue;
            }
            List<Candidate> candidates = searchGeoapify(query, territory, centerLat, centerLng, true);
            Candidate bestCandidate = chooseBestCandidate(
                    candidates, query, territory, centerLat, centerLng, true);
            if (bestCandidate != null) {
                log.debug("Dirección resuelta por Geoapify.");
                return new GeocodingResult(
                        new Coordinates(bestCandidate.lat(), bestCandidate.lng(), bestCandidate.displayName()),
                        false, radiusKm);
            }
        }

        Optional<Coordinates> namedPlace = placesService.findNamedHealthcarePlace(
                address, context, centerLat, centerLng, radiusKm);
        if (namedPlace.isPresent()) return new GeocodingResult(namedPlace.get(), false, radiusKm);

        if (checkOutsideCoverage) {
            for (String query : queries) {
                if (!hasReliableLocationTokens(query)) continue;
                List<Candidate> unrestricted = searchGeoapify(query, territory, centerLat, centerLng, false);
                Candidate matchingInsideCoverage = chooseBestCandidate(
                        unrestricted, query, territory, centerLat, centerLng, true);
                if (matchingInsideCoverage != null) {
                    return new GeocodingResult(
                            new Coordinates(matchingInsideCoverage.lat(), matchingInsideCoverage.lng(),
                                    matchingInsideCoverage.displayName()),
                            false, radiusKm);
                }
                Candidate matchingCandidate = chooseBestCandidate(
                        unrestricted, query, territory, centerLat, centerLng, false);
                if (matchingCandidate != null) {
                    double distanceKm = distanceKilometers(
                            centerLat, centerLng, matchingCandidate.lat(), matchingCandidate.lng());
                    if (distanceKm <= radiusKm) {
                        return new GeocodingResult(
                                new Coordinates(matchingCandidate.lat(), matchingCandidate.lng(),
                                        matchingCandidate.displayName()),
                                false, radiusKm);
                    }
                    outsideCoverage = true;
                }
            }

            Optional<Coordinates> unrestrictedPlace = placesService.findNamedHealthcarePlace(
                    address, context, centerLat, centerLng, radiusKm, false);
            if (unrestrictedPlace.isPresent()) {
                Coordinates place = unrestrictedPlace.get();
                double distanceKm = distanceKilometers(centerLat, centerLng, place.lat(), place.lng());
                if (distanceKm <= radiusKm) {
                    return new GeocodingResult(place, false, radiusKm);
                }
                outsideCoverage = true;
            }
        }

        log.warn("No se pudo geocodificar la dirección dentro de {} km.", radiusKm);
        return new GeocodingResult(Coordinates.EMPTY, outsideCoverage, radiusKm);
    }

    private String normalizeBaseUrl(String url) {
        String endpoint = url == null ? "" : url.strip();
        return Set.of("disabled", "none", "off").contains(endpoint.toLowerCase(Locale.ROOT))
                ? "" : endpoint.replaceAll("/+$", "");
    }

    private List<Candidate> searchGeoapify(
            String query, String territory, double centerLat, double centerLng, boolean restrictToCoverage) {
        if (geoapifyApiKey.isBlank() || geoapifyBaseUrl.isBlank()) return List.of();
        String normalized = normalizeQuery(query);
        if (normalized.isBlank()) return List.of();
        String text = normalized + (territory == null || territory.isBlank() ? "" : ", " + territory) + ", " + country;
        String cacheKey = String.join("|", text, Double.toString(centerLat), Double.toString(centerLng),
                Double.toString(radiusKm), Boolean.toString(restrictToCoverage));
        List<Candidate> cachedCandidates = geocodeCache.get(cacheKey);
        if (cachedCandidates != null) return cachedCandidates;
        try {
            StringBuilder url = new StringBuilder(geoapifyBaseUrl)
                    .append("?text=").append(URLEncoder.encode(text, StandardCharsets.UTF_8))
                    .append("&apiKey=").append(URLEncoder.encode(geoapifyApiKey, StandardCharsets.UTF_8))
                    .append("&format=json&limit=10");
            if (restrictToCoverage) {
                long radiusMeters = Math.max(1000L, Math.round(radiusKm * 1000));
                String circle = "circle:" + centerLng + "," + centerLat + "," + radiusMeters;
                url.append("&filter=").append(URLEncoder.encode(circle, StandardCharsets.UTF_8));
            }
            url.append("&bias=").append(URLEncoder.encode("proximity:" + centerLng + "," + centerLat,
                    StandardCharsets.UTF_8));
            URI uri = URI.create(url.toString());
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .header("User-Agent", userAgent)
                    .timeout(Duration.ofSeconds(7)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("Geoapify respondió HTTP {}.", resp.statusCode());
                return List.of();
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode results = root.get("results");
            if (results == null || !results.isArray()) return List.of();
            List<Candidate> candidates = new ArrayList<>();
            for (JsonNode r : results) {
                JsonNode lat = r.get("lat");
                JsonNode lon = r.get("lon");
                if (lat == null || lon == null) continue;
                String displayName = r.path("formatted").asText("");
                String resultType = r.path("result_type").asText("");
                String municipality = r.path("city").asText("");
                if (municipality.isBlank()) municipality = r.path("suburb").asText("");
                if (municipality.isBlank()) municipality = r.path("county").asText("");
                if (municipality.isBlank()) municipality = r.path("state").asText("");
                candidates.add(new Candidate(lat.asDouble(), lon.asDouble(), displayName, resultType, municipality));
            }
            geocodeCache.put(cacheKey, candidates);
            saveCache(geocodeCache);
            return candidates;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Geoapify request was interrupted.");
            return List.of();
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Error consultando Geoapify: {}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    private Candidate chooseBestCandidate(
            List<Candidate> candidates, String query, String territory,
            double centerLat, double centerLng, boolean requireCoverage) {
        if (candidates.isEmpty()) return null;
        String q = normalizeText(query);
        String terr = territory == null ? "" : normalizeText(territory);
        if (!hasReliableLocationTokens(query)) return null;
        Candidate bestCandidate = null;
        double bestScore = 0.0;
        for (Candidate c : candidates) {
            double distanceKm = distanceKilometers(centerLat, centerLng, c.lat(), c.lng());
            if (!Double.isFinite(distanceKm) || requireCoverage && distanceKm > radiusKm) continue;

            String displayName = normalizeText(c.displayName());
            String municipality = normalizeText(c.municipality());
            if (!matchesSpecificLocation(query, displayName)) continue;
            double similarity = similarity(q, displayName);
            double territoryBonus = (!terr.isBlank() && (municipality.contains(terr) || terr.contains(municipality))) ? 0.30 : 0.0;
            double typeBonus = isAddressType(c.resultType()) ? 0.05 : 0.0;
            // La cercanía al centro de búsqueda (operador o punto fijo) sigue
            // sumando como señal de desempate entre candidatos igual de
            // parecidos textualmente, además de ser ahora también un filtro.
            double proximityBonus;
            if (distanceKm <= 3) proximityBonus = 0.12;
            else if (distanceKm <= 10) proximityBonus = 0.08;
            else if (distanceKm <= 20) proximityBonus = 0.04;
            else proximityBonus = 0.0;
            double score = similarity + territoryBonus + typeBonus + proximityBonus;
            if (score > bestScore) {
                bestScore = score;
                bestCandidate = c;
            }
        }
        // Evita aceptar un candidato que no coincide suficientemente con el texto.
        return bestScore >= ACCEPTANCE_THRESHOLD ? bestCandidate : null;
    }

    static boolean hasReliableLocationTokens(String query) {
        List<String> tokens = specificLocationTokens(query);
        return tokens.size() >= 2 || (tokens.size() == 1 && containsHouseNumber(query));
    }

    static boolean matchesSpecificLocation(String query, String candidate) {
        List<String> tokens = specificLocationTokens(query);
        String normalizedCandidate = normalizeText(candidate);
        long matchingTokens = tokens.stream()
                .filter(token -> Arrays.stream(normalizedCandidate.split("\\s+"))
                        .anyMatch(candidateToken -> wordSimilarity(token, candidateToken) >= 0.82))
                .count();
        return matchingTokens >= Math.ceil(tokens.size() * 0.75);
    }

    private static List<String> specificLocationTokens(String query) {
        Set<String> genericLocationWords = Set.of(
                "hospital", "clinica", "centro", "medico", "calle", "avenida", "av",
                "pasaje", "pje", "ruta", "camino", "carretera", "autopista",
                "caletera", "esquina", "sector", "comuna", "chile", "numero",
                "norte", "sur", "oriente", "poniente"
        );
        return Arrays.stream(normalizeText(query).split("\\s+"))
                .filter(token -> token.length() >= 3)
                .filter(token -> !token.matches("\\d+"))
                .filter(token -> !genericLocationWords.contains(token))
                .toList();
    }

    private static boolean containsHouseNumber(String query) {
        return query != null && query.matches("(?s).*\\b\\d{1,6}\\b.*");
    }

    static double distanceKilometers(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371.0088;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private boolean isAddressType(String resultType) {
        String t = normalizeText(resultType);
        return t.contains("street") || t.contains("road") || t.contains("house") || t.contains("building");
    }

    private double similarity(String a, String b) {
        if (a.isBlank() || b.isBlank()) return 0;
        if (b.contains(a) || a.contains(b)) return 1.0;
        String[] qa = a.split("\\s+");
        String[] qb = b.split("\\s+");
        double suma = 0;
        int usados = 0;
        for (String x : qa) {
            if (x.length() < 3) continue;
            double max = 0;
            for (String y : qb) max = Math.max(max, wordSimilarity(x, y));
            suma += max;
            usados++;
        }
        return usados == 0 ? 0 : suma / usados;
    }

    private static double wordSimilarity(String a, String b) {
        if (a.equals(b)) return 1;
        if (a.length() >= 5 && b.length() >= 5 && (a.startsWith(b.substring(0, Math.min(5, b.length())))
                || b.startsWith(a.substring(0, Math.min(5, a.length()))))) return 0.86;
        int max = Math.max(a.length(), b.length());
        if (max == 0) return 1;
        int d = levenshteinDistance(a, b);
        return 1.0 - ((double) d / max);
    }

    private static int levenshteinDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[b.length()];
    }

    private String extractTerritory(String context, String address) {
        if (context != null) {
            java.util.regex.Matcher explicitMunicipality = java.util.regex.Pattern.compile(
                    "(?i)\\bcomuna\\s+(?:de\\s+)?([\\p{L}][\\p{L}'-]*(?:\\s+[\\p{L}][\\p{L}'-]*){0,2})(?=\\s*(?:,|\\.|;|$|\\b(?:y|con|donde|hay|necesita|requiere|presenta)\\b))"
            ).matcher(context);
            if (explicitMunicipality.find()) return explicitMunicipality.group(1);

            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "(?i)\\b(?:en|por)\\s+(?:la\\s+)?([A-Za-zÁÉÍÓÚÑáéíóúñ'-]{4,30})(?=\\s|,|$)"
            ).matcher(context);
            while (m.find()) {
                String candidate = m.group(1);
                if (!isCommonWord(candidate)) return candidate;
            }
        }
        if (address != null) {
            String[] parts = address.split(",");
            for (int i = parts.length - 1; i >= 0; i--) {
                String candidate = parts[i].trim();
                if (!candidate.isBlank() && !isCommonWord(candidate)
                        && !candidate.matches(".*\\d.*")) {
                    return candidate;
                }
            }

        }
        return null;
    }

    private boolean isCommonWord(String s) {
        String t = normalizeText(s);
        return Set.of(
                "chile", "numero", "parte", "lado", "cerca", "comuna",
                "calle", "pasaje", "avenida", "av", "sector", "poblacion",
                "villa", "cerro", "camino", "ruta", "carretera", "caletera",
                "autopista", "callejon", "esquina", "hospital", "clinica",
                "centro", "medico", "samu", "bomberos"
        ).contains(t);
    }

    static String extractPlace(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "(?i)(?:en|por|cerca de|frente a|junto a|al lado de|detras de|atras de)\\s+(?:la|el|un|una|los|las)?\\s*([A-Za-zÁÉÍÓÚÑáéíóúñ0-9 .'-]{4,70}?)(?=,|\\by\\s+(?=(?:hay|necesita|requiere|presenta|est[aá]n?)\\b)|\\b(?:ubicad[ao]|queda|está|esta|en|por|donde|porque)\\b|$)"
        ).matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    private String extractLocationContext(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "(?i)(?:ubicad[ao]|queda|esta|está|se\\s+encuentra)\\s+(?:al|a la|en|por)\\s+([^,.]{3,70}?)(?=\\s*(?:,|\\by\\s+(?=(?:hay|necesita|requiere|presenta|est[aá]n?)\\b)|\\b(?:donde|porque|necesita|requiere|presenta|se\\s+encuentra)\\b|$))(?:\\s*,\\s*(?:en\\s+)?([^,.]{3,40}))?"
        ).matcher(text);
        if (!m.find()) return null;
        String place = m.group(1).strip();
        String municipality = m.group(2) == null ? "" : m.group(2).strip();
        return municipality.isBlank() ? place : place + ", " + municipality;
    }

    private String normalizeQuery(String text) {
        String t = text.strip();
        return t.replaceAll("(?i)\\b(esquina con|con|a la altura de)\\b", " ")
                .replaceAll("\\s+", " ").trim();
    }

    static String normalizeText(String text) {
        if (text == null) return "";
        String t = java.text.Normalizer.normalize(text.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replaceAll("[^a-z0-9ñ\\s]", " ")
                .replaceAll("\\s+", " ").trim();
        return t;
    }

    private Map<String, List<Candidate>> loadCache() {
        Map<String, List<Candidate>> result = new HashMap<>();
        if (!Files.exists(cachePath)) return result;
        try {
            JsonNode root = mapper.readTree(Files.readString(cachePath));
            root.fields().forEachRemaining(entry -> {
                List<Candidate> items = new ArrayList<>();
                JsonNode v = entry.getValue();
                if (v.isArray()) {
                    for (JsonNode c : v) {
                        items.add(new Candidate(
                                c.path("lat").asDouble(), c.path("lng").asDouble(),
                                c.path("nombre").asText(""), c.path("tipo").asText(""), c.path("comuna").asText("")));
                    }
                } else if (v.has("lat") && v.has("lng")) {
                    // Formato antiguo (una sola coordenada por clave): se conserva como candidato único.
                    items.add(new Candidate(v.get("lat").asDouble(), v.get("lng").asDouble(), "", "", ""));
                }
                result.put(entry.getKey(), items);
            });
        } catch (IOException e) {
            log.warn("No se pudo cargar la caché de geocodificación: {}", e.getClass().getSimpleName());
        }
        return result;
    }

    private void saveCache(Map<String, List<Candidate>> cache) {
        try {
            Files.createDirectories(cachePath.getParent());
            Map<String, List<Map<String, Object>>> flatCache = new HashMap<>();
            cache.forEach((k, items) -> {
                List<Map<String, Object>> values = new ArrayList<>();
                for (Candidate c : items) {
                    values.add(Map.of(
                            "lat", c.lat(), "lng", c.lng(),
                            "nombre", c.displayName(), "tipo", c.resultType(), "comuna", c.municipality()));
                }
                flatCache.put(k, values);
            });
            Files.writeString(cachePath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(flatCache));
        } catch (IOException e) {
            log.warn("No se pudo guardar la caché de geocodificación: {}", e.getClass().getSimpleName());
        }
    }
}
