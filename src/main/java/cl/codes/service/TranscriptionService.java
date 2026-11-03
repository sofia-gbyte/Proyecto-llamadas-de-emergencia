package cl.codes.service;

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
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public TranscriptionService(
            @Value("${app.groq.api-key:}") String apiKey,
            @Value("${app.groq.base-url:https://api.groq.com/openai/v1}") String baseUrl,
            @Value("${app.groq.model:whisper-large-v3-turbo}") String model,
            @Value("${app.groq.language:es-CL}") String language
    ) {
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.baseUrl = baseUrl == null ? "https://api.groq.com/openai/v1" : baseUrl.replaceAll("/+$", "");
        this.model = model == null || model.isBlank() ? "whisper-large-v3-turbo" : model.strip();
        this.language = language == null || language.isBlank() ? "es-CL" : language.strip();
    }

    public String transcribe(Path audio) throws IOException, InterruptedException {
        if (apiKey.isBlank()) {
            throw new IOException("Groq API key is not configured. Set app.groq.api-key or GROQ_API_KEY.");
        }
        if (!Files.exists(audio)) {
            throw new IOException("Audio file not found: " + audio);
        }
        if (Files.size(audio) > 25L * 1024 * 1024) {
            throw new IOException("Groq transcription supports audio files up to 25 MB.");
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

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Groq transcription failed with HTTP " + response.statusCode() + ".");
        }

        JsonNode json = mapper.readTree(response.body());
        String text = json.path("text").asText("").trim();
        if (text.isBlank()) {
            throw new IOException("Groq returned an empty transcript.");
        }
        return text;
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
