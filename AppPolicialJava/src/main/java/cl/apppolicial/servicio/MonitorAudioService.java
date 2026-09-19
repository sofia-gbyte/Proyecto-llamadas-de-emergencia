package cl.apppolicial.servicio;

import cl.apppolicial.clasificador.Clasificador;
import cl.apppolicial.modelo.Llamada;
import cl.apppolicial.repositorio.LlamadaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;

/**
 * Observa la carpeta de audios crudos con java.nio.file.WatchService
 * (equivalente nativo de Java al watchdog de Python, sin dependencias
 * extra). Al llegar un archivo nuevo: transcribe, clasifica, geocodifica,
 * cifra el original y guarda el registro en la base de datos.
 *
 * Corre en un hilo aparte para no bloquear las peticiones REST mientras
 * se transcribe un audio largo.
 */
@Service
public class MonitorAudioService {

    private static final Logger log = LoggerFactory.getLogger(MonitorAudioService.class);
    private static final DateTimeFormatter MARCA_TIEMPO = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final Path carpetaAudioCrudo;
    private final Path carpetaAudioEncriptado;
    private final TranscripcionService transcripcionService;
    private final GeocoderService geocoderService;
    private final SeguridadService seguridadService;
    private final LlamadaRepository repo;
    private final ObjectMapper mapper = new ObjectMapper();

    public MonitorAudioService(
            @Value("${app.audio-crudo-dir}") String audioCrudoDir,
            @Value("${app.audio-encriptado-dir}") String audioEncriptadoDir,
            TranscripcionService transcripcionService,
            GeocoderService geocoderService,
            SeguridadService seguridadService,
            LlamadaRepository repo
    ) throws IOException {
        this.carpetaAudioCrudo = Path.of(audioCrudoDir);
        this.carpetaAudioEncriptado = Path.of(audioEncriptadoDir);
        this.transcripcionService = transcripcionService;
        this.geocoderService = geocoderService;
        this.seguridadService = seguridadService;
        this.repo = repo;
        Files.createDirectories(carpetaAudioCrudo);
        Files.createDirectories(carpetaAudioEncriptado);
    }

    @PostConstruct
    public void iniciar() {
        Thread hiloMonitor = new Thread(this::observarCarpeta, "monitor-audio");
        hiloMonitor.setDaemon(true);
        hiloMonitor.start();
        log.info("Monitoreando carpeta: {}", carpetaAudioCrudo.toAbsolutePath());
    }

    private void observarCarpeta() {
        try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
            carpetaAudioCrudo.register(watcher, ENTRY_CREATE);

            while (true) {
                WatchKey key = watcher.take(); // bloquea hasta que haya un evento
                for (WatchEvent<?> evento : key.pollEvents()) {
                    Path nombreArchivo = (Path) evento.context();
                    Path rutaCompleta = carpetaAudioCrudo.resolve(nombreArchivo);
                    if (esAudioValido(rutaCompleta)) {
                        // Pequeña espera para asegurar que el archivo terminó de escribirse.
                        Thread.sleep(2000);
                        procesarAudio(rutaCompleta);
                    }
                }
                key.reset();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            log.error("Error observando la carpeta de audios", e);
        }
    }

    private boolean esAudioValido(Path ruta) {
        String nombre = ruta.getFileName().toString().toLowerCase();
        return nombre.endsWith(".wav") || nombre.endsWith(".mp3") || nombre.endsWith(".m4a");
    }

    private void procesarAudio(Path rutaAudio) {
        log.info("Nuevo audio detectado: {}", rutaAudio);
        try {
            String transcripcion = transcripcionService.transcribir(rutaAudio);
            log.info("Transcripción: {}", transcripcion);

            Clasificador.ResultadoClasificacion clasificacion = Clasificador.clasificarLlamada(transcripcion);
            log.info("Prioridad asignada: {}", clasificacion.prioridad());

            GeocoderService.Coordenadas coords = geocoderService.geocodificar(clasificacion.direccion(), transcripcion);

            String marca = LocalDateTime.now().format(MARCA_TIEMPO);
            Path rutaEncriptada = carpetaAudioEncriptado.resolve(marca + "_" + rutaAudio.getFileName() + ".enc");
            seguridadService.encriptarArchivo(rutaAudio, rutaEncriptada);

            Llamada llamada = new Llamada();
            llamada.setAudioOriginal(rutaEncriptada.toString());
            llamada.setTranscripcion(transcripcion);
            llamada.setPrioridad(clasificacion.prioridad());
            llamada.setPuntajeUrgente(clasificacion.puntajes().getOrDefault("urgente", 0));
            llamada.setPuntajeRoja(clasificacion.puntajes().getOrDefault("roja", 0));
            llamada.setPuntajeMedia(clasificacion.puntajes().getOrDefault("media", 0));
            llamada.setPuntajeVerde(clasificacion.puntajes().getOrDefault("verde", 0));
            llamada.setPalabrasDestacadas(mapper.writeValueAsString(clasificacion.palabrasDestacadas()));
            llamada.setDireccionDetectada(clasificacion.direccion());
            llamada.setLatitud(coords.lat());
            llamada.setLongitud(coords.lng());
            llamada.setIpOrigen("localhost");
            llamada.setUsuarioCreacion("sistema-monitor");

            Llamada guardada = repo.save(llamada);
            log.info("Llamada guardada con ID {} (prioridad {})", guardada.getId(), guardada.getPrioridad());

        } catch (Exception e) {
            log.error("Error procesando audio {}", rutaAudio, e);
        }
    }
}
