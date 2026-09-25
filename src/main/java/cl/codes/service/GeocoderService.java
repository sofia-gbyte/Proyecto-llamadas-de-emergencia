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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * Geocodificación tolerante para texto escrito y transcriptiones ASR.
 *
 * Estrategia:
 *  1) Búsqueda estructurada en Nominatim usando la calle detectada
 *     (parámetro "street"), mucho más precisa que texto libre.
 *  2) Nominatim con la consulta en texto libre, puntuando cada resultado
 *     por similitud contra lo que dijo el operador (antes se aceptaba el
 *     primer resultado sin comparar nada, lo que hacía que direcciones
 *     mal transcritas terminaran ancladas en un lugar cualquiera).
 *  3) Si hay comuna/territorio, obtiene su centro y lo usa como contexto
 *     espacial.
 *  4) Photon (también basado en OpenStreetMap) devuelve varios candidatos
 *     y permite aprovechar coincidencias aproximadas del nombre, algo útil
 *     cuando el ASR escribe "Manuel Mond" en vez de "Manuel Montt".
 *  5) Todos los candidatos (Nominatim + Photon) se puntúan juntos por
 *     similitud textual + coincidencia territorial y se elige el mejor.
 *
 * La transcripción original nunca se modifica; solo se normaliza la consulta.
 */
@Service
public class GeocoderService {
    private static final Logger log = LoggerFactory.getLogger(GeocoderService.class);

    /** Nominatim pide un máximo de 1 solicitud por segundo desde un mismo origen. */
    private static final long NOMINATIM_MIN_INTERVALO_MS = 1100;
    private static final double UMBRAL_ACEPTACION = 0.55;

    public record Coordinates(Double lat, Double lng) {
        static final Coordinates EMPTY = new Coordinates(null, null);
    }

    private record Candidato(double lat, double lng, String nombre, String tipo, String comuna) {}

    private final Path cachePath;
    private final String pais;
    private final String userAgent;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private long ultimaLlamadaNominatim = 0L;

    public GeocoderService(
            @Value("${app.geocache-path}") String geocachePath,
            @Value("${app.geocoder.pais}") String pais,
            @Value("${app.geocoder.user-agent}") String userAgent
    ) {
        this.cachePath = Path.of(geocachePath);
        this.pais = pais;
        this.userAgent = userAgent;
    }

    public Coordinates geocode(String address) {
        return geocode(address, null);
    }

    public Coordinates geocode(String address, String context) {
        return geocode(address, context, null, null);
    }

    /**
     * Variante para llamadas en vivo: la ubicación del navegador del operator
     * se usa únicamente como señal secundaria para ordenar candidatos del mapa.
     * No se guarda en la llamada ni sustituye la ubicación hablada.
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
        Coordinates centroTerritorio = territorio == null ? Coordinates.EMPTY :
                geocodeNominatimSimple(territorio + ", Chile");
        Coordinates centroBusqueda = (sourceLatitude != null && sourceLongitude != null)
                ? new Coordinates(sourceLatitude, sourceLongitude) : centroTerritorio;

        // 1) Búsqueda estructurada: mucho más confiable que texto libre porque
        //    le decimos a Nominatim explícitamente que "address" es una calle.
        if (address != null && !address.isBlank()) {
            String calle = limpiarNombreCalle(address);
            if (calle != null && !calle.isBlank()) {
                List<Candidato> estructurados = buscarNominatimEstructurado(calle, territorio);
                Candidato mejorEstructurado = elegirMejor(estructurados, calle, territorio, sourceLatitude, sourceLongitude);
                if (mejorEstructurado != null) {
                    log.debug("Dirección resuelta por búsqueda estructurada: '{}' -> {}", calle, mejorEstructurado.nombre());
                    return new Coordinates(mejorEstructurado.lat(), mejorEstructurado.lng());
                }
            }
        }

        // 2) Texto libre en Nominatim y Photon, puntuando TODOS los candidatos
        //    juntos en vez de aceptar a ciegas el primer resultado.
        for (String consulta : consultas) {
            List<Candidato> candidatos = new ArrayList<>();
            candidatos.addAll(buscarNominatimLibre(consulta));
            candidatos.addAll(buscarPhoton(consulta, centroBusqueda));
            Candidato mejor = elegirMejor(candidatos, consulta, territorio, sourceLatitude, sourceLongitude);
            if (mejor != null) {
                log.debug("Dirección resuelta por búsqueda libre: '{}' -> {}", consulta, mejor.nombre());
                return new Coordinates(mejor.lat(), mejor.lng());
            }
        }

        // 3) Último intento: agregar el territorio explícito a la consulta.
        if (territorio != null) {
            for (String consulta : consultas) {
                List<Candidato> candidatos = buscarNominatimLibre(consulta + ", " + territorio);
                Candidato mejor = elegirMejor(candidatos, consulta, territorio, sourceLatitude, sourceLongitude);
                if (mejor != null) {
                    log.debug("Dirección resuelta agregando territorio '{}': '{}' -> {}", territorio, consulta, mejor.nombre());
                    return new Coordinates(mejor.lat(), mejor.lng());
                }
            }
        }

        log.warn("No se pudo geocodificar ninguna de las consultas candidatas: {}", consultas);
        return Coordinates.EMPTY;
    }

    /** Quita el tipo de vía ("Calle", "Avenida"...) y el número, dejando solo el nombre. */
    private String limpiarNombreCalle(String address) {
        String texto = address.replaceAll(
                "(?i)^(calle|pasaje|avenida|av\\.?|sector|poblacion|villa|cerro|camino)\\s+", "");
        texto = texto.replaceAll("(?i)\\s*#?\\s*\\d{1,5}$", "");
        return texto.strip();
    }

    private Coordinates geocodeNominatimSimple(String address) {
        List<Candidato> resultado = buscarNominatimLibre(address);
        if (resultado.isEmpty()) return Coordinates.EMPTY;
        Candidato c = resultado.get(0);
        return new Coordinates(c.lat(), c.lng());
    }

    /** Búsqueda estructurada: le indicamos a Nominatim que "calle" es una vía, no texto libre. */
    private List<Candidato> buscarNominatimEstructurado(String calle, String territorio) {
        StringBuilder url = new StringBuilder("https://nominatim.openstreetmap.org/search?street=")
                .append(URLEncoder.encode(calle, StandardCharsets.UTF_8))
                .append("&country=").append(URLEncoder.encode(pais, StandardCharsets.UTF_8))
                .append("&format=json&limit=5&addressdetails=1&countrycodes=cl");
        if (territorio != null && !territorio.isBlank()) {
            url.append("&city=").append(URLEncoder.encode(territorio, StandardCharsets.UTF_8));
        }
        return ejecutarNominatim(url.toString(), "estructurada(" + calle + ")");
    }

    private List<Candidato> buscarNominatimLibre(String address) {
        String consulta = normalizarConsulta(address);
        if (consulta.isBlank()) return List.of();
        String url = "https://nominatim.openstreetmap.org/search?q="
                + URLEncoder.encode(consulta + ", " + pais, StandardCharsets.UTF_8)
                + "&format=json&limit=5&addressdetails=1&countrycodes=cl";
        return ejecutarNominatim(url, "libre(" + consulta + ")");
    }

    private List<Candidato> ejecutarNominatim(String url, String etiquetaDebug) {
        Map<String, List<Candidato>> cache = cargarCache();
        List<Candidato> cacheados = cache.get(url);
        if (cacheados != null) return cacheados;

        respetarLimiteNominatim();
        List<Candidato> resultado = new ArrayList<>();
        try {
            URI uri = URI.create(url);
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .header("User-Agent", userAgent)
                    .timeout(Duration.ofSeconds(6)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("Nominatim respondió HTTP {} para consulta {}", resp.statusCode(), etiquetaDebug);
                return resultado;
            }
            JsonNode arr = mapper.readTree(resp.body());
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    if (n.get("lat") == null || n.get("lon") == null) continue;
                    JsonNode addr = n.get("address");
                    String comuna = "";
                    if (addr != null) {
                        comuna = addr.path("city").asText("");
                        if (comuna.isBlank()) comuna = addr.path("town").asText("");
                        if (comuna.isBlank()) comuna = addr.path("suburb").asText("");
                        if (comuna.isBlank()) comuna = addr.path("municipality").asText("");
                        if (comuna.isBlank()) comuna = addr.path("county").asText("");
                    }
                    String nombre = n.path("display_name").asText("");
                    String tipo = n.path("type").asText("");
                    resultado.add(new Candidato(
                            n.get("lat").asDouble(), n.get("lon").asDouble(), nombre, tipo, comuna));
                }
            }
            if (resultado.isEmpty()) {
                log.debug("Nominatim sin resultados para consulta {}", etiquetaDebug);
            }
            cache.put(url, resultado);
            guardarCache(cache);
        } catch (Exception e) {
            log.warn("Error consultando Nominatim ({}): {}", etiquetaDebug, e.toString());
        }
        return resultado;
    }

    /** Nominatim exige como máximo 1 solicitud por segundo por origen; respetamos eso aquí. */
    private synchronized void respetarLimiteNominatim() {
        long ahora = System.currentTimeMillis();
        long espera = NOMINATIM_MIN_INTERVALO_MS - (ahora - ultimaLlamadaNominatim);
        if (espera > 0) {
            try {
                Thread.sleep(espera);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        ultimaLlamadaNominatim = System.currentTimeMillis();
    }

    private List<Candidato> buscarPhoton(String consulta, Coordinates centro) {
        List<Candidato> resultado = new ArrayList<>();
        try {
            StringBuilder url = new StringBuilder("https://photon.komoot.io/api/?q=")
                    .append(URLEncoder.encode(normalizarConsulta(consulta), StandardCharsets.UTF_8))
                    .append("&limit=10");
            if (centro.lat() != null && centro.lng() != null) {
                url.append("&lat=").append(centro.lat()).append("&lon=").append(centro.lng());
            }
            URI uri = URI.create(url.toString());
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .header("User-Agent", userAgent)
                    .timeout(Duration.ofSeconds(7)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("Photon respondió HTTP {} para consulta '{}'", resp.statusCode(), consulta);
                return resultado;
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode features = root.get("features");
            if (features == null || !features.isArray()) return resultado;
            for (JsonNode f : features) {
                JsonNode geom = f.get("geometry");
                JsonNode coords = geom == null ? null : geom.get("coordinates");
                JsonNode prop = f.get("properties");
                if (coords == null || coords.size() < 2) continue;
                double lon = coords.get(0).asDouble();
                double lat = coords.get(1).asDouble();
                String nombre = prop == null ? "" : prop.path("name").asText("");
                String tipo = prop == null ? "" : prop.path("type").asText("");
                String comuna = prop == null ? "" : prop.path("city").asText("");
                if (comuna.isBlank() && prop != null) comuna = prop.path("district").asText("");
                if (comuna.isBlank() && prop != null) comuna = prop.path("locality").asText("");
                if (comuna.isBlank() && prop != null) comuna = prop.path("county").asText("");
                resultado.add(new Candidato(lat, lon, nombre, tipo, comuna));
            }
        } catch (Exception e) {
            log.warn("Error consultando Photon para '{}': {}", consulta, e.toString());
        }
        return resultado;
    }

    private Candidato elegirMejor(List<Candidato> candidatos, String consulta, String territorio, Double latitudOrigen, Double longitudOrigen) {
        if (candidatos.isEmpty()) return null;
        String q = normalizarTexto(consulta);
        String terr = territorio == null ? "" : normalizarTexto(territorio);
        Candidato mejor = null;
        double mejorPuntaje = 0.0;
        for (Candidato c : candidatos) {
            String nombre = normalizarTexto(c.nombre());
            String comuna = normalizarTexto(c.comuna());
            double similitud = similitud(q, nombre);
            double territorioBonus = (!terr.isBlank() && (comuna.contains(terr) || terr.contains(comuna))) ? 0.30 : 0.0;
            double tipoBonus = tipoDireccion(c.tipo()) ? 0.05 : 0.0;
            // La ubicación del operator es solo una señal secundaria. Ayuda a
            // desempatar errores de ASR sin asumir que la emergencia ocurre allí.
            double cercaniaBonus = 0.0;
            if (latitudOrigen != null && longitudOrigen != null) {
                double km = distanciaKm(latitudOrigen, longitudOrigen, c.lat(), c.lng());
                if (km <= 3) cercaniaBonus = 0.12;
                else if (km <= 10) cercaniaBonus = 0.08;
                else if (km <= 25) cercaniaBonus = 0.04;
            }
            double score = similitud + territorioBonus + tipoBonus + cercaniaBonus;
            if (score > mejorPuntaje) {
                mejorPuntaje = score;
                mejor = c;
            }
        }
        // Evita aceptar un candidato completamente ajeno al texto: antes esto
        // solo se aplicaba a Photon, y Nominatim se aceptaba sin puntuar.
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
        String texto = contexto == null ? "" : contexto;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?i)(?:,|\\ben\\s+|\\bde\\s+|\\ben la\\s+)([A-Za-zÁÉÍÓÚÑáéíóúñ]{4,30})(?:\\s|,|$)"
        ).matcher(texto);
        String candidato = null;
        while (m.find()) candidato = m.group(1);
        if (candidato != null && !esPalabraComun(candidato)) return candidato;
        if (direccion != null) {
            String[] partes = direccion.split(",");
            if (partes.length >= 2) return partes[partes.length - 1].trim();
        }
        return null;
    }

    private boolean esPalabraComun(String s) {
        String t = normalizarTexto(s);
        return Set.of("santiago", "chile", "numero", "parte", "lado", "cerca", "calle", "avenida").contains(t);
    }

    private String extraerLugar(String texto) {
        if (texto == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?i)(?:en|por|cerca de|frente a|junto a)\\s+(?:la|el|un|una)?\\s*([A-Za-zÁÉÍÓÚÑáéíóúñ0-9 .'-]{4,70}?)(?=,|\\b(?:ubicad[ao]|queda|está|esta|en|por)\\b|$)"
        ).matcher(texto);
        return m.find() ? m.group(1).trim() : null;
    }

    private String extraerContextoUbicacion(String texto) {
        if (texto == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?i)(?:ubicad[ao]|queda|esta|está)\\s+(?:al|a la|en|por)\\s+([^,.]{3,70})(?:\\s*,\\s*(?:en\\s+)?([^,.]{3,40}))?"
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
        } catch (IOException ignored) {}
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
        } catch (IOException ignored) {}
    }
}
