package cl.codes.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Local fallback transcription for audio files received after the upload is complete.
 */
@Service
public class TranscriptionService {

    private final String executable;
    private final String encoder;
    private final String decoder;
    private final String joiner;
    private final String tokens;
    private final String language;
    private final int threads;

    public TranscriptionService(
            @Value("${app.asr.command:sherpa-onnx}") String executable,
            @Value("${app.asr.encoder:}") String encoder,
            @Value("${app.asr.decoder:}") String decoder,
            @Value("${app.asr.joiner:}") String joiner,
            @Value("${app.asr.tokens:}") String tokens,
            @Value("${app.asr.idioma:es-ES}") String language,
            @Value("${app.asr.hilos:2}") int threads
    ) {
        this.executable = executable;
        this.encoder = encoder;
        this.decoder = decoder;
        this.joiner = joiner;
        this.tokens = tokens;
        this.language = language;
        this.threads = threads;
    }

    public String transcribe(Path audio) throws IOException, InterruptedException {
        if (encoder.isBlank() || decoder.isBlank() || joiner.isBlank() || tokens.isBlank()) {
            throw new IOException("Local ASR is not configured. Set app.asr.encoder/decoder/joiner/tokens.");
        }

        List<String> command = new ArrayList<>(List.of(
                executable,
                "--encoder=" + encoder,
                "--decoder=" + decoder,
                "--joiner=" + joiner,
                "--tokens=" + tokens,
                "--language=" + language,
                "--num-threads=" + threads,
                audio.toString()
        ));

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();

        boolean finished = process.waitFor(10, TimeUnit.MINUTES);
        String output = new String(process.getInputStream().readAllBytes());
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("ASR transcription exceeded the maximum wait time");
        }
        if (process.exitValue() != 0) {
            throw new IOException("sherpa-onnx failed: " + output);
        }

        String[] lines = output.lines()
                .map(String::strip)
                .filter(s -> !s.isBlank())
                .toArray(String[]::new);
        if (lines.length == 0) {
            throw new IOException("sherpa-onnx did not return a transcription");
        }

        return lines[lines.length - 1].replaceFirst("^\\{\\s*\\\"text\\\"\\s*:\\s*\\\"", "")
                .replaceFirst("\\\"\\s*[,}]\\s*$", "")
                .strip();
    }
}


