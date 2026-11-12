package cl.codes.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TranscriptionServiceTest {
    @TempDir
    Path tempDir;

    private HttpServer server;
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<Integer> responseStatus = new AtomicReference<>(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>("{\"text\":\"Choque en Avenida Manuel Montt\"}");

    @BeforeEach
    void startGroqStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/openai/v1/audio/transcriptions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus.get(), response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
    }

    @AfterEach
    void stopGroqStub() {
        server.stop(0);
    }

    @Test
    void sendsWebmAudioToGroqAndReturnsTranscript() throws Exception {
        Path audio = tempDir.resolve("recording.webm");
        Files.writeString(audio, "fake webm audio");
        TranscriptionService service = service("test-key");

        String transcript = service.transcribe(audio);

        assertEquals("Choque en Avenida Manuel Montt", transcript);
        assertEquals("Bearer test-key", authorization.get());
        assertTrue(requestBody.get().contains("filename=\"recording.webm\""));
        assertTrue(requestBody.get().contains("Content-Type: audio/webm"));
        assertTrue(requestBody.get().contains("whisper-large-v3-turbo"));
        assertTrue(requestBody.get().contains("name=\"language\"\r\n\r\nes"));
    }

    @Test
    void rejectsAudioWhenGroqKeyIsMissing() throws Exception {
        Path audio = tempDir.resolve("recording.webm");
        Files.writeString(audio, "fake webm audio");
        TranscriptionService service = service("");

        IOException error = assertThrows(IOException.class, () -> service.transcribe(audio));

        assertTrue(error.getMessage().contains("GROQ_API_KEY"));
        assertNull(authorization.get());
    }

    @Test
    void doesNotSendAudioWhenExternalServicesAreDisabled() throws Exception {
        Path audio = tempDir.resolve("recording.webm");
        Files.writeString(audio, "fake webm audio");
        TranscriptionService service = service("test-key", false);

        IOException error = assertThrows(IOException.class, () -> service.transcribe(audio));

        assertTrue(error.getMessage().contains("desactivada"));
        assertNull(authorization.get());
        assertNull(requestBody.get());
    }

    @Test
    void explainsInvalidGroqApiKeyWithoutExposingProviderResponse() throws Exception {
        Path audio = tempDir.resolve("recording.webm");
        Files.writeString(audio, "fake webm audio");
        responseStatus.set(401);
        responseBody.set("{\"error\":\"sensitive-provider-response\"}");

        IOException error = assertThrows(IOException.class, () -> service("test-key").transcribe(audio));

        assertTrue(error.getMessage().contains("GROQ_API_KEY"));
        assertFalse(error.getMessage().contains("sensitive-provider-response"));
    }

    @Test
    void explainsQuotaLimitWithoutExposingProviderResponse() throws Exception {
        Path audio = tempDir.resolve("recording.webm");
        Files.writeString(audio, "fake webm audio");
        responseStatus.set(429);
        responseBody.set("{\"error\":\"sensitive-provider-response\"}");

        IOException error = assertThrows(IOException.class, () -> service("test-key").transcribe(audio));

        assertTrue(error.getMessage().contains("cuota"));
        assertFalse(error.getMessage().contains("sensitive-provider-response"));
    }

    @Test
    void explainsTemporaryProviderFailuresWithoutExposingProviderResponse() throws Exception {
        Path audio = tempDir.resolve("recording.webm");
        Files.writeString(audio, "fake webm audio");
        responseStatus.set(503);
        responseBody.set("{\"error\":\"sensitive-provider-response\"}");

        IOException error = assertThrows(IOException.class, () -> service("test-key").transcribe(audio));

        assertTrue(error.getMessage().contains("temporal"));
        assertTrue(error.getMessage().contains("503"));
        assertFalse(error.getMessage().contains("sensitive-provider-response"));
    }

    @Test
    void explainsUnsupportedAudioWithoutExposingProviderResponse() throws Exception {
        Path audio = tempDir.resolve("recording.webm");
        Files.writeString(audio, "fake webm audio");
        responseStatus.set(422);
        responseBody.set("{\"error\":\"sensitive-provider-response\"}");

        IOException error = assertThrows(IOException.class, () -> service("test-key").transcribe(audio));

        assertTrue(error.getMessage().contains("formato de audio"));
        assertFalse(error.getMessage().contains("sensitive-provider-response"));
    }

    @Test
    void rejectsMalformedProviderResponseWithoutExposingItsBody() throws Exception {
        Path audio = tempDir.resolve("recording.webm");
        Files.writeString(audio, "fake webm audio");
        responseBody.set("provider-secret-body");

        IOException error = assertThrows(IOException.class, () -> service("test-key").transcribe(audio));

        assertTrue(error.getMessage().contains("formato inesperado"));
        assertFalse(error.getMessage().contains("provider-secret-body"));
    }

    private TranscriptionService service(String apiKey) {
        return service(apiKey, true);
    }

    private TranscriptionService service(String apiKey, boolean externalServicesEnabled) {
        return new TranscriptionService(
                apiKey,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/openai/v1",
                "whisper-large-v3-turbo",
                "es-CL",
                externalServicesEnabled
        );
    }
}
