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

    private static final double UMBRAL_ACEPTACION = 0.55;

    public record Coordinates(Double lat, Double lng, String formattedAddress) {
        static final Coordinates EMPTY = new Coordinates(null, null, null);
        public Coordinates(Double lat, Double lng) {
            this(lat, lng, null);
        }
        boolean presente() { return lat != null && lng != null; }
    }

    private record Candidato(double lat, double lng, String nombre, String tipo, String comuna) {}

    private final Path cachePath;
    private final String pais;
    private final String userAgent;
    private final String geoapifyApiKey;
    private final String geoapifyBaseUrl;
    private final double centroLatDefecto;
    private final double centroLngDefecto;
    private final double radioKm;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    // La caché de geocodificación se mantiene en memoria y se carga una sola
    // vez al iniciar. Antes se releía y reescribía el archivo COMPLETO en
    // cada consulta de geocodificación (varias por llamada procesada); con
    // miles de direcciones cacheadas eso se vuelve un costo de disco que
    // crece con el tiempo. Ahora las lecturas son instantáneas (memoria) y
    // solo se escribe a disco cuando aparece una dirección nueva.
    private final Map<String, List<Candidato>> geocacheMemoria = new java.util.concurrent.ConcurrentHashMap<>();

    @PostConstruct
    void cargarCacheInicial() {
        geocacheMemoria.putAll(cargarCache());
        log.info("Caché de geocodificación cargada en memoria: {} entradas", geocacheMemoria.size());
    }

    public GeocoderService(
            @Value("${app.geocache-path}") String geocachePath,
            @Value("${app.geocoder.pais}") String pais,
            @Value("${app.geocoder.user-agent}") String userAgent,
            @Value("${app.geoapify.api-key:}") String geoapifyApiKey,
            @Value("${app.geoapify.base-url:https://api.geoapify.com/v1/geocode/search}") String geoapifyBaseUrl,
            @Value("${app.geocoder.centro-lat:-33.4489}") double centroLatDefecto,
            @Value("${app.geocoder.centro-lng:-70.6693}") double centroLngDefecto,
            @Value("${app.geocoder.radio-km:30}") double radioKm
    ) {
        this.cachePath = Path.of(geocachePath);
        this.pais = pais;
        this.userAgent = userAgent;
        this.geoapifyApiKey = geoapifyApiKey == null ? "" : geoapifyApiKey.strip();
        this.geoapifyBaseUrl = normalizarBaseUrl(geoapifyBaseUrl);
        this.centroLatDefecto = centroLatDefecto;
        this.centroLngDefecto = centroLngDefecto;
        this.radioKm = radioKm;
    }

    public Coordinates geocode(String address) {
        return geocode(address, null);
    }

    public Coordinates geocode(String address, String context) {
        return geocode(address, context, null, null);
    }

    /**
    * Variante para llamadas en vivo. Las coordenadas del operador no sustituyen
    * la ubicación hablada ni limitan la búsqueda del incidente.
     */
    public Coordinates geocode(String address, String context, Double sourceLatitude, Double sourceLongitude) {
        LinkedHashSet<String> consultas = new LinkedHashSet<>();
        if (address != null && !address.isBlank()) consultas.add(address.strip());
        if (context != null && !context.isBlank()) {
            String ubicacion = extraerContextoUbicacion(context);
            if (ubicacion != null && !ubicacion.isBlank()) consultas.add(ubicacion);
            String lugar = extraerLugar(context);
            if (lugar != null && !lugar.isBlank()) consultas.add(lugar);
        }
        if (consultas.isEmpty()) {
            log.debug("Geocodificación omitida: no hay dirección ni contexto utilizable.");
            return Coordinates.EMPTY;
        }

        String territorio = extraerTerritorio(context, address);

        // El área de búsqueda representa la cobertura de la central, no la
        // ubicación del operador, que puede estar lejos del incidente.
        double centroLat = centroLatDefecto;
        double centroLng = centroLngDefecto;
        if (geoapifyApiKey.isBlank() || geoapifyBaseUrl.isBlank()) {
            log.warn("Geoapify no está configurado; se omitió la consulta externa.");
            return Coordinates.EMPTY;
        }

        // Geoapify busca solo dentro del radio operativo y la coincidencia
        // territorial se usa tanto para construir la consulta como para puntuar.
        for (String consulta : consultas) {
            List<Candidato> candidatos = buscarGeoapify(consulta, territorio, centroLat, centroLng);
            Candidato mejor = elegirMejor(candidatos, consulta, territorio, centroLat, centroLng);
            if (mejor != null) {
                log.debug("Dirección resuelta por Geoapify.");
                return new Coordinates(mejor.lat(), mejor.lng(), mejor.nombre());
            }
        }

        log.warn("No se pudo geocodificar la dirección dentro de {} km.", radioKm);
        return Coordinates.EMPTY;
    }

    private String normalizarBaseUrl(String url) {
        String endpoint = url == null ? "" : url.strip();
        return Set.of("disabled", "none", "off").contains(endpoint.toLowerCase(Locale.ROOT))
                ? "" : endpoint.replaceAll("/+$", "");
    }

    private List<Candidato> buscarGeoapify(String consulta, String territorio, double centroLat, double centroLng) {
        if (geoapifyApiKey.isBlank() || geoapifyBaseUrl.isBlank()) return List.of();
        String normalized = normalizarConsulta(consulta);
        if (normalized.isBlank()) return List.of();
        String texto = normalized + (territorio == null || territorio.isBlank() ? "" : ", " + territorio) + ", " + pais;
        String cacheKey = String.join("|", texto, Double.toString(centroLat), Double.toString(centroLng), Double.toString(radioKm));
        List<Candidato> cacheados = geocacheMemoria.get(cacheKey);
        if (cacheados != null) return cacheados;
        try {
            long radioMetros = Math.max(1000L, Math.round(radioKm * 1000));
            String circulo = "circle:" + centroLng + "," + centroLat + "," + radioMetros;
            StringBuilder url = new StringBuilder(geoapifyBaseUrl)
                    .append("?text=").append(URLEncoder.encode(texto, StandardCharsets.UTF_8))
                    .append("&apiKey=").append(URLEncoder.encode(geoapifyApiKey, StandardCharsets.UTF_8))
                    .append("&format=json&limit=10")
                    .append("&filter=").append(URLEncoder.encode(circulo, StandardCharsets.UTF_8))
                    .append("&bias=").append(URLEncoder.encode("proximity:" + centroLng + "," + centroLat, StandardCharsets.UTF_8));
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
            List<Candidato> candidatos = new ArrayList<>();
            for (JsonNode r : results) {
                JsonNode lat = r.get("lat");
                JsonNode lon = r.get("lon");
                if (lat == null || lon == null) continue;
                String nombre = r.path("formatted").asText("");
                String tipo = r.path("result_type").asText("");
                String comuna = r.path("city").asText("");
                if (comuna.isBlank()) comuna = r.path("suburb").asText("");
                if (comuna.isBlank()) comuna = r.path("county").asText("");
                if (comuna.isBlank()) comuna = r.path("state").asText("");
                candidatos.add(new Candidato(lat.asDouble(), lon.asDouble(), nombre, tipo, comuna));
            }
            geocacheMemoria.put(cacheKey, candidatos);
            guardarCache(geocacheMemoria);
            return candidatos;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Geoapify request was interrupted.");
            return List.of();
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Error consultando Geoapify: {}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    private Candidato elegirMejor(List<Candidato> candidatos, String consulta, String territorio, double centroLat, double centroLng) {
        if (candidatos.isEmpty()) return null;
        String q = normalizarTexto(consulta);
        String terr = territorio == null ? "" : normalizarTexto(territorio);
        Candidato mejor = null;
        double mejorPuntaje = 0.0;
        for (Candidato c : candidatos) {
            // Filtro duro de cobertura: sin importar cuánto se parezca el
            // nombre, un candidato fuera del radio configurado NUNCA se elige.
            double km = distanciaKm(centroLat, centroLng, c.lat(), c.lng());
            if (km > radioKm) continue;

            String nombre = normalizarTexto(c.nombre());
            String comuna = normalizarTexto(c.comuna());
            double similitud = similitud(q, nombre);
            double territorioBonus = (!terr.isBlank() && (comuna.contains(terr) || terr.contains(comuna))) ? 0.30 : 0.0;
            double tipoBonus = tipoDireccion(c.tipo()) ? 0.05 : 0.0;
            // La cercanía al centro de búsqueda (operador o punto fijo) sigue
            // sumando como señal de desempate entre candidatos igual de
            // parecidos textualmente, además de ser ahora también un filtro.
            double cercaniaBonus;
            if (km <= 3) cercaniaBonus = 0.12;
            else if (km <= 10) cercaniaBonus = 0.08;
            else if (km <= 20) cercaniaBonus = 0.04;
            else cercaniaBonus = 0.0;
            double score = similitud + territorioBonus + tipoBonus + cercaniaBonus;
            if (score > mejorPuntaje) {
                mejorPuntaje = score;
                mejor = c;
            }
        }
        // Evita aceptar un candidato que no coincide suficientemente con el texto.
        return mejorPuntaje >= UMBRAL_ACEPTACION ? mejor : null;
    }

    private double distanciaKm(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371.0088;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private boolean tipoDireccion(String tipo) {
        String t = normalizarTexto(tipo);
        return t.contains("street") || t.contains("road") || t.contains("house") || t.contains("building");
    }

    private double similitud(String a, String b) {
        if (a.isBlank() || b.isBlank()) return 0;
        if (b.contains(a) || a.contains(b)) return 1.0;
        String[] qa = a.split("\\s+");
        String[] qb = b.split("\\s+");
        double suma = 0;
        int usados = 0;
        for (String x : qa) {
            if (x.length() < 3) continue;
            double max = 0;
            for (String y : qb) max = Math.max(max, similitudPalabra(x, y));
            suma += max;
            usados++;
        }
        return usados == 0 ? 0 : suma / usados;
    }

    private double similitudPalabra(String a, String b) {
        if (a.equals(b)) return 1;
        if (a.length() >= 5 && b.length() >= 5 && (a.startsWith(b.substring(0, Math.min(5, b.length())))
                || b.startsWith(a.substring(0, Math.min(5, a.length()))))) return 0.86;
        int max = Math.max(a.length(), b.length());
        if (max == 0) return 1;
        int d = distanciaLevenshtein(a, b);
        return 1.0 - ((double) d / max);
    }

    private int distanciaLevenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int costo = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + costo);
            }
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[b.length()];
    }

    private String extraerTerritorio(String contexto, String direccion) {
        if (contexto != null) {
            java.util.regex.Matcher comunaExplicita = java.util.regex.Pattern.compile(
                    "(?i)\\bcomuna\\s+(?:de\\s+)?([\\p{L}][\\p{L}'-]*(?:\\s+[\\p{L}][\\p{L}'-]*){0,2})(?=\\s*(?:,|\\.|;|$|\\b(?:y|con|donde|hay|necesita|requiere|presenta)\\b))"
            ).matcher(contexto);
            if (comunaExplicita.find()) return comunaExplicita.group(1);

            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "(?i)\\b(?:en|por)\\s+(?:la\\s+)?([A-Za-zÁÉÍÓÚÑáéíóúñ'-]{4,30})(?=\\s|,|$)"
            ).matcher(contexto);
            while (m.find()) {
                String candidato = m.group(1);
                if (!esPalabraComun(candidato)) return candidato;
            }
        }
        if (direccion != null) {
            String[] partes = direccion.split(",");
            for (int i = partes.length - 1; i >= 0; i--) {
                String candidato = partes[i].trim();
                if (!candidato.isBlank() && !esPalabraComun(candidato)
                        && !candidato.matches(".*\\d.*")) {
                    return candidato;
                }
            }
        }
        return null;
    }

    private boolean esPalabraComun(String s) {
        String t = normalizarTexto(s);
        return Set.of(
                "chile", "numero", "parte", "lado", "cerca", "comuna",
                "calle", "pasaje", "avenida", "av", "sector", "poblacion",
                "villa", "cerro", "camino", "ruta", "carretera", "caletera",
                "autopista", "callejon", "esquina"
        ).contains(t);
    }

    private String extraerLugar(String texto) {
        if (texto == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "(?i)(?:en|por|cerca de|frente a|junto a|al lado de|detras de|atras de)\\s+(?:la|el|un|una|los|las)?\\s*([A-Za-zÁÉÍÓÚÑáéíóúñ0-9 .'-]{4,70}?)(?=,|\\by\\s+(?=(?:hay|necesita|requiere|presenta|est[aá]n?)\\b)|\\b(?:ubicad[ao]|queda|está|esta|en|por|donde|porque)\\b|$)"
        ).matcher(texto);
        return m.find() ? m.group(1).trim() : null;
    }

    private String extraerContextoUbicacion(String texto) {
        if (texto == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "(?i)(?:ubicad[ao]|queda|esta|está|se\\s+encuentra)\\s+(?:al|a la|en|por)\\s+([^,.]{3,70}?)(?=\\s*(?:,|\\by\\s+(?=(?:hay|necesita|requiere|presenta|est[aá]n?)\\b)|\\b(?:donde|porque|necesita|requiere|presenta|se\\s+encuentra)\\b|$))(?:\\s*,\\s*(?:en\\s+)?([^,.]{3,40}))?"
        ).matcher(texto);
        if (!m.find()) return null;
        String lugar = m.group(1).strip();
        String comuna = m.group(2) == null ? "" : m.group(2).strip();
        return comuna.isBlank() ? lugar : lugar + ", " + comuna;
    }

    private String normalizarConsulta(String texto) {
        String t = texto.strip();
        return t.replaceAll("(?i)\\b(esquina con|con|a la altura de)\\b", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private String normalizarTexto(String texto) {
        if (texto == null) return "";
        String t = java.text.Normalizer.normalize(texto.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replaceAll("[^a-z0-9ñ\\s]", " ")
                .replaceAll("\\s+", " ").trim();
        return t;
    }

    private Map<String, List<Candidato>> cargarCache() {
        Map<String, List<Candidato>> resultado = new HashMap<>();
        if (!Files.exists(cachePath)) return resultado;
        try {
            JsonNode raiz = mapper.readTree(Files.readString(cachePath));
            raiz.fields().forEachRemaining(entry -> {
                List<Candidato> lista = new ArrayList<>();
                JsonNode v = entry.getValue();
                if (v.isArray()) {
                    for (JsonNode c : v) {
                        lista.add(new Candidato(
                                c.path("lat").asDouble(), c.path("lng").asDouble(),
                                c.path("nombre").asText(""), c.path("tipo").asText(""), c.path("comuna").asText("")));
                    }
                } else if (v.has("lat") && v.has("lng")) {
                    // Formato antiguo (una sola coordenada por clave): se conserva como candidato único.
                    lista.add(new Candidato(v.get("lat").asDouble(), v.get("lng").asDouble(), "", "", ""));
                }
                resultado.put(entry.getKey(), lista);
            });
        } catch (IOException e) {
            log.warn("No se pudo cargar la caché de geocodificación: {}", e.getClass().getSimpleName());
        }
        return resultado;
    }

    private void guardarCache(Map<String, List<Candidato>> cache) {
        try {
            Files.createDirectories(cachePath.getParent());
            Map<String, List<Map<String, Object>>> plano = new HashMap<>();
            cache.forEach((k, lista) -> {
                List<Map<String, Object>> valores = new ArrayList<>();
                for (Candidato c : lista) {
                    valores.add(Map.of(
                            "lat", c.lat(), "lng", c.lng(),
                            "nombre", c.nombre(), "tipo", c.tipo(), "comuna", c.comuna()));
                }
                plano.put(k, valores);
            });
            Files.writeString(cachePath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(plano));
        } catch (IOException e) {
            log.warn("No se pudo guardar la caché de geocodificación: {}", e.getClass().getSimpleName());
        }
    }
}
