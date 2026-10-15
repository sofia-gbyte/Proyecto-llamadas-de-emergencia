package cl.codes.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Arranca el servidor WebSocket de Sherpa automáticamente junto con CODES.
 * Si el puerto 6006 ya está ocupado (por ejemplo, por un servidor iniciado
 * manualmente), no crea otro proceso. Si CODES lo inició, lo detiene al close
 * Spring Boot.
 */
@Service
public class AsrStreamingService {
    private static final Logger log = LoggerFactory.getLogger(AsrStreamingService.class);
    private final boolean autoStart;
    private final Path root;
    private final String modelName;
    private final String language;
    private final int threads;
    private Process proceso;

    public AsrStreamingService(
            @Value("${app.asr.auto-start:true}") boolean autoStart,
            @Value("${app.asr.root:./tools/asr}") String asrRoot,
            @Value("${app.asr.model-name:sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11}") String modelName,
            @Value("${app.asr.idioma:es-ES}") String language,
            @Value("${app.asr.hilos:2}") int threads
    ) {
        this.autoStart = autoStart;
        this.root = Path.of(asrRoot).toAbsolutePath().normalize();
        this.modelName = modelName;
        this.language = language;
        this.threads = threads;
    }

    @PostConstruct
    public void iniciar() {
        if (!autoStart || !esWindows()) {
            if (!esWindows()) log.info("ASR auto-start: este equipo no es Windows; se conserva el arranque manual.");
            return;
        }
        if (puertoOcupado(6006)) {
            log.info("ASR WebSocket ya está disponible en ws://localhost:6006. No se inicia otro proceso.");
            return;
        }
        try {
            Path modelo = root.resolve("models").resolve(modelName);
            Path encoder = modelo.resolve("encoder.int8.onnx");
            Path decoder = modelo.resolve("decoder.int8.onnx");
            Path joiner = modelo.resolve("joiner.int8.onnx");
            Path tokens = modelo.resolve("tokens.txt");

            Path serverScript = root.resolve("websocket_server.py");
            if (!java.nio.file.Files.exists(serverScript)) {
                log.warn("No se encontró el servidor ASR Python en {}.", serverScript);
                return;
            }
            if (!java.nio.file.Files.exists(encoder) || !java.nio.file.Files.exists(decoder)
                    || !java.nio.file.Files.exists(joiner) || !java.nio.file.Files.exists(tokens)) {
                log.warn("Falta el modelo ASR en {}. CODES continuará sin arrancar Sherpa.", modelo);
                return;
            }

            List<String> cmd = new ArrayList<>();
            cmd.add("python");
            cmd.add(serverScript.toString());
            cmd.add("--port=6006");
            cmd.add("--threads=" + threads);
            cmd.add("--language=" + language);
            cmd.add("--sample-rate=16000");
            cmd.add("--tokens=" + tokens);
            cmd.add("--encoder=" + encoder);
            cmd.add("--decoder=" + decoder);
            cmd.add("--joiner=" + joiner);

            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .directory(root.toFile())
                    .redirectErrorStream(true);
            proceso = pb.start();

            Thread salida = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(proceso.getInputStream()))) {
                    String linea;
                    while ((linea = br.readLine()) != null) log.info("ASR | {}", linea);
                } catch (Exception ignored) {}
            }, "asr-output");
            salida.setDaemon(true);
            salida.start();

            Thread.sleep(1200);
            if (puertoOcupado(6006)) {
                log.info("✓ ASR streaming iniciado automáticamente en ws://localhost:6006");
            } else {
                log.warn("Sherpa fue iniciado, pero el puerto 6006 todavía no responde. Revisa tools/asr/asr.log.");
            }
        } catch (Exception e) {
            log.warn("No se pudo iniciar Sherpa automáticamente: {}", e.getMessage());
        }
    }

    /** Expuesto para el autochequeo/estado de administrador: true si el WebSocket responde ahora mismo. */
    public boolean estaDisponible() {
        return puertoOcupado(6006);
    }

    private boolean puertoOcupado(int puerto) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", puerto), 250);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean esWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    @PreDestroy
    public void detener() {
        if (proceso == null) return;
        try {
            log.info("Deteniendo ASR streaming...");
            proceso.destroy();
            if (!proceso.waitFor(3, TimeUnit.SECONDS)) proceso.destroyForcibly();
        } catch (Exception e) {
            proceso.destroyForcibly();
        } finally {
            proceso = null;
        }
    }
}


