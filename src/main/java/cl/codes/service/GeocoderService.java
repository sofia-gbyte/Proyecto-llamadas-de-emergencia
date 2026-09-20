package cl.codes.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 *  1) Nominatim/OpenStreetMap con la consulta normal.
 *  2) Si hay comuna/territorio, obtiene su centro y usa ese contexto.
 *  3) Photon (también basado en OpenStreetMap) devuelve varios candidatos
 *     y permite aprovechar coincidencias aproximadas del nombre, algo útil
 *     cuando el ASR escribe "Manuel Mond" en vez de "Manuel Montt".
 *  4) Se puntúan los candidatos por similitud textual + coincidencia territorial.
 *
 * La transcripción original nunca se modifica; solo se normaliza la consulta.
 */
@Service
public class GeocoderService {

    public record Coordenadas(Double lat, Double lng) {
        static final Coordenadas VACIA = new Coordenadas(null, null);
    }

    private record Candidato(double lat, double lng, String nombre, String tipo, String comuna) {}

    private final Path cachePath;
    private final String pais;
    private final String userAgent;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public GeocoderService(
            @Value("${app.geocache-path}") String geocachePath,
            @Value("${app.geocoder.pais}") String pais,
            @Value("${app.geocoder.user-agent}") String userAgent
    ) {
        this.cachePath = Path.of(geocachePath);
        this.pais = pais;
        this.userAgent = userAgent;
    }

    public Coordenadas geocodificar(String direccion) {
        return geocodificar(direccion, null);
    }

    public Coordenadas geocodificar(String direccion, String contexto) {
        return geocodificar(direccion, contexto, null, null);
    }

    /**
     * Variante para llamadas en vivo: la ubicación del navegador del operator
     * se usa únicamente como señal secundaria para ordenar candidatos del mapa.
     * No se guarda en la llamada ni sustituye la ubicación hablada.
     */
    public Coordenadas geocodificar(String direccion, String contexto, Double latitudOrigen, Double longitudOrigen) {
        LinkedHashSet<String> consultas = new LinkedHashSet<>();
        if (direccion != null && !direccion.isBlank()) consultas.add(direccion.strip());
        if (contexto != null && !contexto.isBlank()) {
            String ubicacion = extraerContextoUbicacion(contexto);
            if (ubicacion != null && !ubicacion.isBlank()) consultas.add(ubicacion);
            String lugar = extraerLugar(contexto);
            if (lugar != null && !lugar.isBlank()) consultas.add(lugar);
        }

        // 1. Intento rápido: Nominatim con la consulta normal.
        for (String consulta : consultas) {
            Coordenadas c = geocodificarNominatim(consulta, null, null);
            if (c.lat() != null) return c;
        }

        // 2. Buscar el territorio mencionado (por ejemplo Providencia) y usarlo
        //    como contexto espacial para las búsquedas aproximadas.
        String territorio = extraerTerritorio(contexto, direccion);
        Coordenadas centroTerritorio = territorio == null ? Coordenadas.VACIA :
                geocodificarNominatim(territorio + ", Chile", null, null);

        // 3. Photon/OpenStreetMap: devuelve múltiples candidatos y tolera mejor
        //    pequeñas diferencias de escritura del ASR.
        for (String consulta : consultas) {
            Coordenadas centroBusqueda = (latitudOrigen != null && longitudOrigen != null)
                    ? new Coordenadas(latitudOrigen, longitudOrigen) : centroTerritorio;
            List<Candidato> candidatos = buscarPhoton(consulta, centroBusqueda);
            Candidato mejor = elegirMejor(candidatos, consulta, territorio, latitudOrigen, longitudOrigen);
            if (mejor != null) {
                return new Coordenadas(mejor.lat(), mejor.lng());
            }
        }

        // 4. Último intento: Nominatim con el territorio explícito.
        if (territorio != null) {
            for (String consulta : consultas) {
                Coordenadas c = geocodificarNominatim(consulta + ", " + territorio, null, null);
                if (c.lat() != null) return c;
            }
        }
        return Coordenadas.VACIA;
    }

    private Coordenadas geocodificarNominatim(String direccion, Double lat, Double lon) {
        Map<String, Coordenadas> cache = cargarCache();
        String consulta = normalizarConsulta(direccion);
        String cacheKey = "nominatim:" + consulta + (lat == null ? "" : "@" + lat + "," + lon);
        if (cache.containsKey(cacheKey)) return cache.get(cacheKey);
        try {
            String query = URLEncoder.encode(consulta + ", " + pais, StandardCharsets.UTF_8);
            URI uri = URI.create("https://nominatim.openstreetmap.org/search?q=" + query
                    + "&format=json&limit=5&addressdetails=1&countrycodes=cl");
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .header("User-Agent", userAgent)
                    .timeout(Duration.ofSeconds(6)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return Coordenadas.VACIA;
            JsonNode arr = mapper.readTree(resp.body());
            if (arr.isArray() && !arr.isEmpty()) {
                // En Nominatim ya vienen ordenados por relevancia.
                JsonNode n = arr.get(0);
                Coordenadas c = new Coordenadas(n.get("lat").asDouble(), n.get("lon").asDouble());
                cache.put(cacheKey, c);
                guardarCache(cache);
                return c;
            }
        } catch (Exception ignored) {}
        return Coordenadas.VACIA;
    }

    private List<Candidato> buscarPhoton(String consulta, Coordenadas centro) {
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
            if (resp.statusCode() != 200) return resultado;
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
        } catch (Exception ignored) {}
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
        // Evita aceptar un candidato completamente ajeno al texto.
        return mejorPuntaje >= 0.42 ? mejor : null;
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

    private Map<String, Coordenadas> cargarCache() {
        Map<String, Coordenadas> resultado = new HashMap<>();
        if (!Files.exists(cachePath)) return resultado;
        try {
            JsonNode raiz = mapper.readTree(Files.readString(cachePath));
            raiz.fields().forEachRemaining(entry -> {
                JsonNode v = entry.getValue();
                resultado.put(entry.getKey(), new Coordenadas(
                        v.has("lat") ? v.get("lat").asDouble() : null,
                        v.has("lng") ? v.get("lng").asDouble() : null));
            });
        } catch (IOException ignored) {}
        return resultado;
    }

    private void guardarCache(Map<String, Coordenadas> cache) {
        try {
            Files.createDirectories(cachePath.getParent());
            Map<String, Map<String, Double>> plano = new HashMap<>();
            cache.forEach((k, v) -> {
                if (v.lat() != null && v.lng() != null)
                    plano.put(k, Map.of("lat", v.lat(), "lng", v.lng()));
            });
            Files.writeString(cachePath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(plano));
        } catch (IOException ignored) {}
    }
}


