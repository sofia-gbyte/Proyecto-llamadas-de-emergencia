package cl.codes.service;

import cl.codes.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Autochequeo periódico e invisible para el usuario final: corre solo,
 * en segundo plano, y NO crea ninguna llamada ni dato visible para los
 * operadores. Pensado para que un administrador pueda comprobar "¿está
 * vivo el servidor y sus piezas?" sin depender de que alguien esté
 * mirando la interfaz en ese momento.
 *
 * No reemplaza un monitor externo real (eso sigue en la lista de
 * pendientes para cuando CODES tenga dominio/infraestructura propia),
 * pero para una instalación local, sin dominio ni servicio de monitoreo
 * contratado, deja evidencia automática y reciente de que cada pieza
 * sigue respondiendo.
 */
@Service
public class SelfTestService {
    private static final Logger log = LoggerFactory.getLogger(SelfTestService.class);

    private final UserRepository userRepository;
    private final AsrStreamingService asrStreamingService;
    private final Path dataDir;
    private final Path geocachePath;
    private final double espacioLibreMinimoGb;

    private final AtomicReference<SelfTestResult> ultimoResultado = new AtomicReference<>();

    public SelfTestService(
            UserRepository userRepository,
            AsrStreamingService asrStreamingService,
            @Value("${app.data-dir:./data}") String dataDir,
            @Value("${app.geocache-path:./data/geocache.json}") String geocachePath,
            @Value("${app.selftest.min-free-space-gb:1.0}") double espacioLibreMinimoGb
    ) {
        this.userRepository = userRepository;
        this.asrStreamingService = asrStreamingService;
        this.dataDir = Path.of(dataDir);
        this.geocachePath = Path.of(geocachePath);
        this.espacioLibreMinimoGb = espacioLibreMinimoGb;
    }

    public record CheckResult(String nombre, boolean ok, String detalle) {}

    public record SelfTestResult(LocalDateTime timestamp, String estadoGeneral, List<CheckResult> checks) {}

    /** Último resultado conocido; si todavía no corrió ninguno, lo ejecuta ahora mismo. */
    public SelfTestResult ultimo() {
        SelfTestResult actual = ultimoResultado.get();
        return actual != null ? actual : ejecutar();
    }

    @Scheduled(
            initialDelayString = "${app.selftest.initial-delay-ms:30000}",
            fixedDelayString = "${app.selftest.interval-ms:600000}" // cada 10 min por defecto
    )
    public SelfTestResult ejecutar() {
        List<CheckResult> checks = new ArrayList<>();
        boolean fallaCritica = false;

        // 1. Base de datos: una lectura simple y barata, sin tocar datos operativos.
        try {
            userRepository.count();
            checks.add(new CheckResult("base_de_datos", true, "Consulta de prueba respondida correctamente"));
        } catch (Exception e) {
            checks.add(new CheckResult("base_de_datos", false, "No respondió: " + e.getMessage()));
            fallaCritica = true;
        }

        // 2. Disco: escritura y borrado de un archivo temporal invisible dentro de data/.
        try {
            Files.createDirectories(dataDir);
            Path prueba = dataDir.resolve(".selftest_tmp");
            Files.writeString(prueba, "selftest " + LocalDateTime.now());
            Files.deleteIfExists(prueba);
            checks.add(new CheckResult("disco_escritura", true, "Escritura y borrado de prueba correctos"));
        } catch (Exception e) {
            checks.add(new CheckResult("disco_escritura", false, "No se pudo escribir en " + dataDir + ": " + e.getMessage()));
            fallaCritica = true;
        }

        // 3. Espacio libre en disco (los audios y la base de datos crecen con el tiempo).
        try {
            File carpeta = dataDir.toAbsolutePath().toFile();
            double libreGb = carpeta.getUsableSpace() / (1024.0 * 1024.0 * 1024.0);
            boolean ok = libreGb >= espacioLibreMinimoGb;
            checks.add(new CheckResult("espacio_libre", ok,
                    String.format("%.1f GB libres (mínimo configurado: %.1f GB)", libreGb, espacioLibreMinimoGb)));
        } catch (Exception e) {
            checks.add(new CheckResult("espacio_libre", false, "No se pudo calcular: " + e.getMessage()));
        }

        // 4. ASR en vivo. No es crítico: CODES sigue operando con transcripción manual si esto falla.
        boolean asrOk = asrStreamingService.estaDisponible();
        checks.add(new CheckResult("asr_streaming", asrOk,
                asrOk ? "ws://localhost:6006 responde" : "No responde; la transcripción manual sigue disponible"));

        // 5. Carpeta de caché de geocodificación. Tampoco crítico.
        try {
            Path carpetaCache = geocachePath.toAbsolutePath().getParent();
            boolean ok = carpetaCache == null || Files.isDirectory(carpetaCache) || carpetaCache.toFile().mkdirs();
            checks.add(new CheckResult("geocache", ok, ok ? "Carpeta de caché accesible" : "No se pudo acceder a " + carpetaCache));
        } catch (Exception e) {
            checks.add(new CheckResult("geocache", false, "No se pudo verificar: " + e.getMessage()));
        }

        String estado = fallaCritica ? "FALLA"
                : checks.stream().anyMatch(c -> !c.ok()) ? "DEGRADADO"
                : "OK";

        SelfTestResult resultado = new SelfTestResult(LocalDateTime.now(), estado, checks);
        ultimoResultado.set(resultado);

        switch (estado) {
            case "OK" -> log.debug("Autochequeo interno: OK");
            case "DEGRADADO" -> log.warn("Autochequeo interno: DEGRADADO -> {}", checks);
            default -> log.error("Autochequeo interno: FALLA -> {}", checks);
        }
        return resultado;
    }
}
