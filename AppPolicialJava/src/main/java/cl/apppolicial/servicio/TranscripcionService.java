package cl.apppolicial.servicio;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Transcripción local para audios que llegan como archivo completo.
 *
 * La ruta principal en CODES ahora es streaming ASR con sherpa-onnx desde
 * el navegador. Este servicio queda como respaldo para los audios que llegan
 * por la carpeta data/audios_crudos/ una vez que el archivo está completo.
 *
 * No usa la implementación Python de Whisper.
 */
@Service
public class TranscripcionService {

    private final String ejecutable;
    private final String encoder;
    private final String decoder;
    private final String joiner;
    private final String tokens;
    private final String idioma;
    private final int hilos;

    public TranscripcionService(
            @Value("${app.asr.command:sherpa-onnx}") String ejecutable,
            @Value("${app.asr.encoder:}") String encoder,
            @Value("${app.asr.decoder:}") String decoder,
            @Value("${app.asr.joiner:}") String joiner,
            @Value("${app.asr.tokens:}") String tokens,
            @Value("${app.asr.idioma:es-ES}") String idioma,
            @Value("${app.asr.hilos:2}") int hilos
    ) {
        this.ejecutable = ejecutable;
        this.encoder = encoder;
        this.decoder = decoder;
        this.joiner = joiner;
        this.tokens = tokens;
        this.idioma = idioma;
        this.hilos = hilos;
    }

    public String transcribir(Path audio) throws IOException, InterruptedException {
        if (encoder.isBlank() || decoder.isBlank() || joiner.isBlank() || tokens.isBlank()) {
            throw new IOException("ASR local no configurado. Configura app.asr.encoder/decoder/joiner/tokens.");
        }

        List<String> comando = new ArrayList<>(List.of(
                ejecutable,
                "--encoder=" + encoder,
                "--decoder=" + decoder,
                "--joiner=" + joiner,
                "--tokens=" + tokens,
                "--language=" + idioma,
                "--num-threads=" + hilos,
                audio.toString()
        ));

        Process proceso = new ProcessBuilder(comando)
                .redirectErrorStream(true)
                .start();

        boolean terminado = proceso.waitFor(10, TimeUnit.MINUTES);
        String salida = new String(proceso.getInputStream().readAllBytes());
        if (!terminado) {
            proceso.destroyForcibly();
            throw new IOException("La transcripción ASR excedió el tiempo máximo esperado");
        }
        if (proceso.exitValue() != 0) {
            throw new IOException("sherpa-onnx falló: " + salida);
        }

        // El binario imprime una línea final con el texto reconocido. Evitamos
        // depender de un archivo temporal como hacía el Whisper CLI anterior.
        String[] lineas = salida.lines()
                .map(String::strip)
                .filter(s -> !s.isBlank())
                .toArray(String[]::new);
        if (lineas.length == 0) {
            throw new IOException("sherpa-onnx no devolvió una transcripción");
        }

        return lineas[lineas.length - 1].replaceFirst("^\\{\\s*\\\"text\\\"\\s*:\\s*\\\"", "")
                .replaceFirst("\\\"\\s*[,}]\\s*$", "")
                .strip();
    }
}
