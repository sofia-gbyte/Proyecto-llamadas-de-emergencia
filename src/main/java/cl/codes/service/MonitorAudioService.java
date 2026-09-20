package cl.codes.service;

import cl.codes.classifier.Classifier;
import cl.codes.model.Call;
import cl.codes.repository.CallRepository;
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

    private final Path rawAudioFolder;
    private final Path encryptedAudioFolder;
    private final TranscriptionService transcriptionService;
    private final GeocoderService geocoderService;
    private final SecurityService securityService;
    private final CallRepository repo;
    private final ObjectMapper mapper = new ObjectMapper();

    public MonitorAudioService(
            @Value("${app.audio-crudo-dir}") String rawAudioDir,
            @Value("${app.audio-encriptado-dir}") String encryptedAudioDir,
            TranscriptionService transcriptionService,
            GeocoderService geocoderService,
            SecurityService securityService,
            CallRepository repo
    ) throws IOException {
        this.rawAudioFolder = Path.of(rawAudioDir);
        this.encryptedAudioFolder = Path.of(encryptedAudioDir);
        this.transcriptionService = transcriptionService;
        this.geocoderService = geocoderService;
        this.securityService = securityService;
        this.repo = repo;
        Files.createDirectories(rawAudioFolder);
        Files.createDirectories(encryptedAudioFolder);
    }

    @PostConstruct
    public void start() {
        Thread monitorThread = new Thread(this::watchFolder, "monitor-audio");
        monitorThread.setDaemon(true);
        monitorThread.start();
        log.info("Watching folder: {}", rawAudioFolder.toAbsolutePath());
    }

    private void watchFolder() {
        try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
            rawAudioFolder.register(watcher, ENTRY_CREATE);

            while (true) {
                WatchKey key = watcher.take(); // bloquea hasta que haya un evento
                for (WatchEvent<?> event : key.pollEvents()) {
                    Path fileName = (Path) event.context();
                    Path fullPath = rawAudioFolder.resolve(fileName);
                    if (isValidAudio(fullPath)) {
                        Thread.sleep(2000);
                        processAudio(fullPath);
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

    private boolean isValidAudio(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".wav") || name.endsWith(".mp3") || name.endsWith(".m4a");
    }

    private void processAudio(Path audioPath) {
        log.info("New audio detected: {}", audioPath);
        try {
            String transcription = transcriptionService.transcribe(audioPath);
            log.info("Transcription: {}", transcription);

            Classifier.ResultadoClasificacion classification = Classifier.classifyCall(transcription);
            log.info("Assigned priority: {}", classification.priority());

            GeocoderService.Coordinates coords = geocoderService.geocode(classification.direccion(), transcription);

            String marker = LocalDateTime.now().format(MARCA_TIEMPO);
            Path encryptedPath = encryptedAudioFolder.resolve(marker + "_" + audioPath.getFileName() + ".enc");
            securityService.encryptFile(audioPath, encryptedPath);

            Call call = new Call();
            call.setOriginalAudio(encryptedPath.toString());
            call.setTranscription(transcription);
            call.setPriority(classification.priority());
            call.setUrgentScore(classification.puntajes().getOrDefault("urgente", 0));
            call.setRedScore(classification.puntajes().getOrDefault("roja", 0));
            call.setMediumScore(classification.puntajes().getOrDefault("media", 0));
            call.setGreenScore(classification.puntajes().getOrDefault("verde", 0));
            call.setHighlightedWords(mapper.writeValueAsString(classification.highlightedWords()));
            call.setDetectedAddress(classification.direccion());
            call.setLatitude(coords.lat());
            call.setLongitude(coords.lng());
            call.setSourceIp("localhost");
            call.setCreatedByUser("system-monitor");

            Call saved = repo.save(call);
            log.info("Call saved with ID {} (priority {})", saved.getId(), saved.getPriority());

        } catch (Exception e) {
            log.error("Error processing audio {}", audioPath, e);
        }
    }
}


