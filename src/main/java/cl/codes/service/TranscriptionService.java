package cl.codes.service;

import cl.codes.exception.ExternalServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

/**
 * Speech-to-text using the Groq Whisper API.
 */
@Service
public class TranscriptionService {

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final String language;
    private final boolean externalServicesEnabled;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public TranscriptionService(
            @Value("${app.groq.api-key:}") String apiKey,
            @Value("${app.groq.base-url:https://api.groq.com/openai/v1}") String baseUrl,
            @Value("${app.groq.model:whisper-large-v3-turbo}") String model,
            @Value("${app.groq.language:es-CL}") String language,
            @Value("${app.external-services.enabled:false}") boolean externalServicesEnabled
    ) {
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.baseUrl = baseUrl == null ? "https://api.groq.com/openai/v1" : baseUrl.replaceAll("/+$", "");
        this.model = model == null || model.isBlank() ? "whisper-large-v3-turbo" : model.strip();
        this.language = language == null || language.isBlank() ? "es-CL" : language.strip();
        this.externalServicesEnabled = externalServicesEnabled;
    }

    public String transcribe(Path audio) throws IOException, InterruptedException {
        if (!externalServicesEnabled) {
            throw new ExternalServiceException("La transcripción en la nube está desactivada. Habilita los proveedores externos en Configuración y reinicia CODES.");
        }
        if (apiKey.isBlank()) {
            throw new ExternalServiceException("Falta la clave GROQ_API_KEY. Configúrala en el menú del EXE y reinicia CODES.");
        }
        if (!Files.exists(audio)) {
            throw new IOException("No se encontró el archivo de audio temporal para transcribir.");
        }
        if (Files.size(audio) > 25_000_000L) {
            throw new ExternalServiceException("Groq admite audios de hasta 25 MB. Detén la grabación antes y vuelve a intentarlo.");
        }

        String boundary = "----GroqBoundary" + UUID.randomUUID();
        String fileName = audio.getFileName() == null ? "audio.wav" : audio.getFileName().toString();
        byte[] payload = buildMultipartBody(audio, fileName, boundary);
        String url = baseUrl + "/audio/transcriptions";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new ExternalServiceException("Groq tardó demasiado en responder. Comprueba la conexión e inténtalo nuevamente.", e);
        } catch (IOException e) {
            throw new ExternalServiceException("No se pudo conectar con Groq. Comprueba la conexión a Internet y la URL configurada.", e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw providerError(response.statusCode());
        }

        JsonNode json;
        try {
            json = mapper.readTree(response.body());
        } catch (IOException e) {
            throw new ExternalServiceException("Groq respondió en un formato inesperado. Inténtalo nuevamente.", e);
        }
        String text = json.path("text").asText("").trim();
        if (text.isBlank()) {
            throw new ExternalServiceException("Groq no detectó voz en la grabación. Comprueba el micrófono o registra la llamada manualmente.");
        }
        return text;
    }

    private ExternalServiceException providerError(int status) {
        return switch (status) {
            case 401, 403 -> new ExternalServiceException(
                    "Groq rechazó la clave API (HTTP " + status + "). Revisa GROQ_API_KEY y reinicia CODES.");
            case 413 -> new ExternalServiceException(
                    "Groq rechazó el audio por su tamaño. El límite de transcripción es 25 MB.");
            case 422 -> new ExternalServiceException(
                    "Groq no pudo procesar el formato de audio o el modelo configurado. Revisa GROQ_MODEL.");
            case 429 -> new ExternalServiceException(
                    "Groq alcanzó el límite de solicitudes o de cuota (HTTP 429). Revisa el plan de la cuenta e inténtalo más tarde.");
            default -> status >= 500
                    ? new ExternalServiceException(
                            "Groq presenta una falla temporal (HTTP " + status + "). Inténtalo nuevamente más tarde.")
                    : new ExternalServiceException(
                            "Groq rechazó la transcripción (HTTP " + status + "). Revisa la configuración del proveedor.");
        };
    }

    private byte[] buildMultipartBody(Path audioFile, String fileName, String boundary) throws IOException {
        String requestLanguage = language;
        if (requestLanguage != null && requestLanguage.startsWith("es-")) {
            requestLanguage = "es";
        }

        byte[] fileBytes = Files.readAllBytes(audioFile);
        String safeFileName = fileName.replaceAll("[^A-Za-z0-9._-]", "_");
        String contentType = contentTypeFor(safeFileName);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        bos.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + safeFileName + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        bos.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        bos.write(fileBytes);
        bos.write("\r\n".getBytes(StandardCharsets.UTF_8));

        bos.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        bos.write("Content-Disposition: form-data; name=\"model\"\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        bos.write(model.getBytes(StandardCharsets.UTF_8));
        bos.write("\r\n".getBytes(StandardCharsets.UTF_8));

        bos.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        bos.write("Content-Disposition: form-data; name=\"language\"\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        bos.write(requestLanguage.getBytes(StandardCharsets.UTF_8));
        bos.write("\r\n".getBytes(StandardCharsets.UTF_8));

        bos.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return bos.toByteArray();
    }

    private String contentTypeFor(String fileName) {
        String name = fileName.toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".webm")) return "audio/webm";
        if (name.endsWith(".ogg")) return "audio/ogg";
        if (name.endsWith(".mp3")) return "audio/mpeg";
        if (name.endsWith(".m4a") || name.endsWith(".mp4")) return "audio/mp4";
        if (name.endsWith(".aac")) return "audio/aac";
        if (name.endsWith(".flac")) return "audio/flac";
        return "audio/wav";
    }
}
