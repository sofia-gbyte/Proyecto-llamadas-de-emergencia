package cl.codes.service;

import cl.codes.classifier.Classifier;
import cl.codes.model.Call;
import cl.codes.repository.CallRepository;
import cl.codes.repository.UserRepository;
import cl.codes.model.User;
import cl.codes.exception.ResourceNotFoundException;
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
 * grabación -> Groq Whisper -> clasificación -> Geoapify -> cifrado -> BD.
 */
@Service
public class LiveCallService {
    private static final DateTimeFormatter FILE_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");
    private static final int MAX_TRANSCRIPTION_CHARS = 20_000;

    private final CallRepository repo;
    private final UserRepository userRepository;
    private final GeocoderService geocoder;
    private final SecurityService securityService;
    private final OperationalSummaryService operationalSummaryService;
    private final TranscriptionService transcriptionService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path encryptedFolder;

    public LiveCallService(
            CallRepository repo,
            UserRepository userRepository,
            GeocoderService geocoder,
            SecurityService securityService,
            OperationalSummaryService operationalSummaryService,
            TranscriptionService transcriptionService,
            @org.springframework.beans.factory.annotation.Value("${app.audio-encriptado-dir}") String encryptedFolder
    ) throws IOException {
        this.repo = repo;
        this.userRepository = userRepository;
        this.geocoder = geocoder;
        this.securityService = securityService;
        this.operationalSummaryService = operationalSummaryService;
        this.transcriptionService = transcriptionService;
        this.encryptedFolder = Path.of(encryptedFolder);
        Files.createDirectories(this.encryptedFolder);
    }

    public Call create(String transcription, MultipartFile audio, String user, String ip) throws Exception {
        return create(transcription, audio, user, ip, null, null);
    }

    public Call create(String transcription, MultipartFile audio, String user, String ip, Double operatorLatitude, Double operatorLongitude) throws Exception {
        validateCoordinates(operatorLatitude, operatorLongitude);
        User creator = userRepository.findByUsername(user).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (creator.isTestAccount()) {
            throw new org.springframework.security.access.AccessDeniedException(
                "Esta es una sesión de vista previa (solo lectura): no puede crear llamadas reales.");
        }
        Path temporaryAudioFile = null;
        Path encryptedAudioFile = null;
        try {
            String sourceTranscript = cleanTranscription(transcription);
            if (audio != null && !audio.isEmpty()) {
                validateAudio(audio);
                String fileName = sanitizeName(audio.getOriginalFilename());
                temporaryAudioFile = Files.createTempFile("codes-live-", "." + extension(fileName));
                try (var input = audio.getInputStream()) {
                    Files.copy(input, temporaryAudioFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                if (!Files.exists(temporaryAudioFile) || Files.size(temporaryAudioFile) == 0) {
                    throw new IllegalStateException("La grabación llegó vacía al servidor.");
                }
                if (sourceTranscript.isBlank()) {
                    sourceTranscript = transcriptionService.transcribe(temporaryAudioFile);
                }
            }
            if (sourceTranscript.isBlank()) {
                throw new IllegalArgumentException("Se necesita una transcripción o un audio para crear la llamada.");
            }

            String transcript = cleanTranscription(sourceTranscript);
            Classifier.ClassificationResult classification = Classifier.classifyCall(transcript);
            if (!Classifier.hasOperationalInformation(classification)) {
                throw new IllegalArgumentException("The transcription has insufficient operational information.");
            }
            GeocoderService.Coordinates coords = geocoder.geocode(classification.address(), transcript, operatorLatitude, operatorLongitude);
            String address = coords.formattedAddress() != null && !coords.formattedAddress().isBlank()
                    ? coords.formattedAddress()
                    : classification.address();

            if (temporaryAudioFile != null) {
                Files.createDirectories(encryptedFolder);
                String fileName = sanitizeName(audio.getOriginalFilename());
                encryptedAudioFile = encryptedFolder.resolve(
                        FILE_TIMESTAMP_FORMAT.format(LocalDateTime.now()) + "_" + fileName + ".enc"
                );
                securityService.encryptFile(temporaryAudioFile, encryptedAudioFile);
                if (!Files.exists(encryptedAudioFile) || Files.size(encryptedAudioFile) <= 12) {
                    throw new IllegalStateException("No fue posible completar el guardado seguro de la grabación.");
                }
            }

            Call call = new Call();
            call.setTranscription(transcript);
            call.setOperationalSummary(operationalSummaryService.generate(transcript, classification, address));
            call.setOriginalAudio(encryptedAudioFile != null ? encryptedAudioFile.toString() : null);
            call.setPriority(classification.priority());
            call.setUrgentScore(classification.scores().getOrDefault("urgente", 0));
            call.setRedScore(classification.scores().getOrDefault("roja", 0));
            call.setMediumScore(classification.scores().getOrDefault("media", 0));
            call.setGreenScore(classification.scores().getOrDefault("verde", 0));
            call.setHighlightedWords(mapper.writeValueAsString(classification.highlightedWords()));
            call.setClassificationReasons(mapper.writeValueAsString(classification.reasons()));
            call.setSuggestedInstitutions(mapper.writeValueAsString(classification.suggestedInstitutions()));
            call.setInstitutionRecommendations(mapper.writeValueAsString(classification.institutionRecommendations()));
            call.setDetectedAddress(address);
            call.setLatitude(coords.lat());
            call.setLongitude(coords.lng());
            call.setSourceIp(ip);
            call.setCreatedByUser(user);
            call.setInstitution(creator.getInstitution());
            return repo.save(call);
        } finally {
            if (temporaryAudioFile != null) Files.deleteIfExists(temporaryAudioFile);
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
        String text = value.strip();
        try {
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(text);
            if (node != null && node.isObject() && node.has("text")) {
                text = node.get("text").asText("").strip();
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalidJson) {
            // A malformed JSON-looking transcription is still treated as user-provided plain text.
        }
        if (text.length() > MAX_TRANSCRIPTION_CHARS) {
            throw new IllegalArgumentException("La transcripción no puede superar 20000 caracteres.");
        }
        return text;
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
