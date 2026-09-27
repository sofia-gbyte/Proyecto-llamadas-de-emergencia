package cl.codes.service;

import cl.codes.classifier.Classifier;
import cl.codes.model.Call;
import cl.codes.repository.CallRepository;
import cl.codes.repository.UserRepository;
import cl.codes.model.User;
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
public class LiveCallService {
    private static final DateTimeFormatter MARCA = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    private final CallRepository repo;
    private final UserRepository userRepository;
    private final GeocoderService geocoder;
    private final ChileStreetCorrectionService streetCorrection;
    private final SecurityService securityService;
    private final OperationalSummaryService operationalSummaryService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path encryptedFolder;

    public LiveCallService(
            CallRepository repo,
            UserRepository userRepository,
            GeocoderService geocoder,
            ChileStreetCorrectionService streetCorrection,
            SecurityService securityService,
            OperationalSummaryService operationalSummaryService,
            @org.springframework.beans.factory.annotation.Value("${app.audio-encriptado-dir}") String encryptedFolder
    ) throws IOException {
        this.repo = repo;
        this.userRepository = userRepository;
        this.geocoder = geocoder;
        this.streetCorrection = streetCorrection;
        this.securityService = securityService;
        this.operationalSummaryService = operationalSummaryService;
        this.encryptedFolder = Path.of(encryptedFolder);
        Files.createDirectories(this.encryptedFolder);
    }

    public Call create(String transcription, MultipartFile audio, String user, String ip) throws Exception {
        return create(transcription, audio, user, ip, null, null);
    }

    public Call create(String transcription, MultipartFile audio, String user, String ip, Double operatorLatitude, Double operatorLongitude) throws Exception {
        validateCoordinates(operatorLatitude, operatorLongitude);
        User creator = userRepository.findByUsername(user).orElseThrow(() -> new IllegalArgumentException("User not found"));
        String texto = streetCorrection.correct(cleanTranscription(transcription));
        Classifier.ResultadoClasificacion classification = Classifier.classifyCall(texto);
        if (!Classifier.hasOperationalInformation(classification)) {
            throw new IllegalArgumentException("The transcription has insufficient operational information.");
        }
        GeocoderService.Coordinates coords = geocoder.geocode(classification.direccion(), texto, operatorLatitude, operatorLongitude);

        Path temporal = null;
        Path encriptado = null;
        try {
            if (audio != null && !audio.isEmpty()) {
                validateAudio(audio);
                String nombre = sanitizeName(audio.getOriginalFilename());
                temporal = Files.createTempFile("codes-live-", "." + extension(nombre));
                try (var input = audio.getInputStream()) {
                    Files.copy(input, temporal, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                if (!Files.exists(temporal) || Files.size(temporal) == 0) {
                    throw new IllegalStateException("La grabación llegó vacía al servidor.");
                }
                Files.createDirectories(encryptedFolder);
                encriptado = encryptedFolder.resolve(
                        MARCA.format(LocalDateTime.now()) + "_" + nombre + ".enc"
                );
                securityService.encryptFile(temporal, encriptado);
                if (!Files.exists(encriptado) || Files.size(encriptado) <= 12) {
                    throw new IllegalStateException("No fue posible completar el guardado seguro de la grabación.");
                }
            }

            Call call = new Call();
            call.setTranscription(texto);
            call.setOperationalSummary(operationalSummaryService.generate(texto, classification));
            call.setOriginalAudio(encriptado != null ? encriptado.toString() : null);
            call.setPriority(classification.priority());
            call.setUrgentScore(classification.puntajes().getOrDefault("urgente", 0));
            call.setRedScore(classification.puntajes().getOrDefault("roja", 0));
            call.setMediumScore(classification.puntajes().getOrDefault("media", 0));
            call.setGreenScore(classification.puntajes().getOrDefault("verde", 0));
            call.setHighlightedWords(mapper.writeValueAsString(classification.highlightedWords()));
            call.setClassificationReasons(mapper.writeValueAsString(classification.motivos()));
            call.setSuggestedInstitutions(mapper.writeValueAsString(classification.institucionesSugeridas()));
            call.setDetectedAddress(classification.direccion());
            call.setLatitude(coords.lat());
            call.setLongitude(coords.lng());
            call.setSourceIp(ip);
            call.setCreatedByUser(user);
            call.setInstitution(creator.getInstitution());
            return repo.save(call);
        } finally {
            if (temporal != null) Files.deleteIfExists(temporal);
        }
    }

    private void validateCoordinates(Double lat, Double lon) {
        if ((lat == null) != (lon == null)) throw new IllegalArgumentException("Operator latitude and longitude must be provided together");
        if (lat != null && (lat.isNaN() || lat.isInfinite() || lat < -90 || lat > 90)) throw new IllegalArgumentException("Invalid operator latitude");
        if (lon != null && (lon.isNaN() || lon.isInfinite() || lon < -180 || lon > 180)) throw new IllegalArgumentException("Invalid operator longitude");
    }

    private void validateAudio(MultipartFile audio) {
        final long maxBytes = 50L * 1024 * 1024;
        if (audio.getSize() > maxBytes) throw new IllegalArgumentException("Audio exceeds the 50 MB limit");
        String contentType = audio.getContentType() == null ? "" : audio.getContentType().toLowerCase().split(";", 2)[0].trim();
        String name = audio.getOriginalFilename() == null ? "" : audio.getOriginalFilename().toLowerCase();
        boolean mimeOk = contentType.equals("audio/webm") || contentType.equals("audio/ogg") || contentType.equals("audio/wav") || contentType.equals("audio/x-wav") || contentType.equals("audio/mpeg") || contentType.equals("audio/mp4") || contentType.equals("audio/aac");
        boolean extOk = name.endsWith(".webm") || name.endsWith(".ogg") || name.endsWith(".wav") || name.endsWith(".mp3") || name.endsWith(".m4a") || name.endsWith(".aac");
        if (!mimeOk || !extOk) throw new IllegalArgumentException("Unsupported audio type");
    }

    private String cleanTranscription(String value) {
        if (value == null || value.isBlank()) return "";
        String texto = value.strip();
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

    private String extension(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) return name.substring(dot + 1);
        return "webm";
    }

    private String sanitizeName(String name) {
        String base = (name == null || name.isBlank()) ? "call.webm" : name;
        return base.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}


