package cl.apppolicial.servicio;

import cl.apppolicial.clasificador.Clasificador;
import cl.apppolicial.modelo.Llamada;
import cl.apppolicial.repositorio.LlamadaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Cierra el ciclo de una llamada iniciada desde el micrófono del navegador:
 * transcripción streaming -> clasificación -> geocodificación -> cifrado -> BD.
 */
@Service
public class LlamadaEnVivoService {
    private static final DateTimeFormatter MARCA = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    private final LlamadaRepository repo;
    private final GeocoderService geocoder;
    private final SeguridadService seguridad;
    private final ResumenOperativoService resumenService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path carpetaEncriptados;

    public LlamadaEnVivoService(
            LlamadaRepository repo,
            GeocoderService geocoder,
            SeguridadService seguridad,
            ResumenOperativoService resumenService,
            @org.springframework.beans.factory.annotation.Value("${app.audio-encriptado-dir}") String carpetaEncriptados
    ) throws IOException {
        this.repo = repo;
        this.geocoder = geocoder;
        this.seguridad = seguridad;
        this.resumenService = resumenService;
        this.carpetaEncriptados = Path.of(carpetaEncriptados);
        Files.createDirectories(this.carpetaEncriptados);
    }

    public Llamada crear(String transcripcion, MultipartFile audio, String usuario, String ip) throws Exception {
        return crear(transcripcion, audio, usuario, ip, null, null);
    }

    public Llamada crear(String transcripcion, MultipartFile audio, String usuario, String ip, Double latitudOperador, Double longitudOperador) throws Exception {
        String texto = limpiarTranscripcion(transcripcion);
        if (texto.isBlank()) throw new IllegalArgumentException("La transcripción está vacía.");
        Clasificador.ResultadoClasificacion clasificacion = Clasificador.clasificarLlamada(texto);
        GeocoderService.Coordenadas coords = geocoder.geocodificar(clasificacion.direccion(), texto, latitudOperador, longitudOperador);

        Path temporal = null;
        Path encriptado = null;
        try {
            if (audio != null && !audio.isEmpty()) {
                String nombre = sanitizarNombre(audio.getOriginalFilename());
                temporal = Files.createTempFile("codes-live-", "-" + nombre);
                audio.transferTo(temporal);
                encriptado = carpetaEncriptados.resolve(
                        MARCA.format(LocalDateTime.now()) + "_" + nombre + ".enc"
                );
                seguridad.encriptarArchivo(temporal, encriptado);
            }

            Llamada llamada = new Llamada();
            llamada.setTranscripcion(texto);
            llamada.setResumenOperativo(resumenService.generar(texto, clasificacion));
            llamada.setAudioOriginal(encriptado != null ? encriptado.toString() : null);
            llamada.setPrioridad(clasificacion.prioridad());
            llamada.setPuntajeUrgente(clasificacion.puntajes().getOrDefault("urgente", 0));
            llamada.setPuntajeRoja(clasificacion.puntajes().getOrDefault("roja", 0));
            llamada.setPuntajeMedia(clasificacion.puntajes().getOrDefault("media", 0));
            llamada.setPuntajeVerde(clasificacion.puntajes().getOrDefault("verde", 0));
            llamada.setPalabrasDestacadas(mapper.writeValueAsString(clasificacion.palabrasDestacadas()));
            llamada.setDireccionDetectada(clasificacion.direccion());
            llamada.setLatitud(coords.lat());
            llamada.setLongitud(coords.lng());
            llamada.setIpOrigen(ip);
            llamada.setUsuarioCreacion(usuario);
            return repo.save(llamada);
        } finally {
            if (temporal != null) Files.deleteIfExists(temporal);
        }
    }

    /** Extrae el texto humano cuando sherpa entrega el resultado JSON completo. */
    private String limpiarTranscripcion(String valor) {
        if (valor == null || valor.isBlank()) return "";
        String texto = valor.strip();
        try {
            com.fasterxml.jackson.databind.JsonNode nodo = mapper.readTree(texto);
            if (nodo != null && nodo.isObject() && nodo.has("text")) {
                return nodo.get("text").asText("").strip();
            }
        } catch (Exception ignored) {
            // No era JSON: ya es texto plano.
        }
        return texto;
    }

    private String sanitizarNombre(String nombre) {
        String base = (nombre == null || nombre.isBlank()) ? "llamada.webm" : nombre;
        return base.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
