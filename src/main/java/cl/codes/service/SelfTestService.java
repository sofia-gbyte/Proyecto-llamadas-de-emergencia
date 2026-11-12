package cl.codes.service;

import cl.codes.repository.UserRepository;
import com.fasterxml.jackson.annotation.JsonProperty;
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
    private final Path dataDir;
    private final Path geocachePath;
    private final double minimumFreeSpaceGb;
    private final boolean groqConfigured;
    private final boolean geoapifyConfigured;

    private final AtomicReference<SelfTestResult> latestResult = new AtomicReference<>();

    public SelfTestService(
            UserRepository userRepository,
            @Value("${app.data-dir:./data}") String dataDir,
            @Value("${app.geocache-path:./data/geocache.json}") String geocachePath,
            @Value("${app.selftest.min-free-space-gb:1.0}") double minimumFreeSpaceGb,
            @Value("${app.groq.api-key:}") String groqApiKey,
            @Value("${app.geoapify.api-key:}") String geoapifyApiKey
    ) {
        this.userRepository = userRepository;
        this.dataDir = Path.of(dataDir);
        this.geocachePath = Path.of(geocachePath);
        this.minimumFreeSpaceGb = minimumFreeSpaceGb;
        this.groqConfigured = groqApiKey != null && !groqApiKey.isBlank();
        this.geoapifyConfigured = geoapifyApiKey != null && !geoapifyApiKey.isBlank();
    }

    public record CheckResult(
            @JsonProperty("nombre") String name,
            boolean ok,
            @JsonProperty("detalle") String details
    ) {}

    public record SelfTestResult(
            LocalDateTime timestamp,
            @JsonProperty("estadoGeneral") String overallStatus,
            List<CheckResult> checks
    ) {}

    /** Último resultado conocido; si todavía no corrió ninguno, lo ejecuta ahora mismo. */
    public SelfTestResult latest() {
        SelfTestResult latest = latestResult.get();
        return latest != null ? latest : runSelfTest();
    }

    @Scheduled(
            initialDelayString = "${app.selftest.initial-delay-ms:30000}",
            fixedDelayString = "${app.selftest.interval-ms:600000}" // cada 10 min por defecto
    )
    public SelfTestResult runSelfTest() {
        List<CheckResult> checks = new ArrayList<>();
        boolean criticalFailure = false;

        // 1. Base de datos: una lectura simple y barata, sin tocar datos operativos.
        try {
            userRepository.count();
            checks.add(new CheckResult("base_de_datos", true, "Consulta de prueba respondida correctamente"));
        } catch (Exception e) {
            checks.add(new CheckResult("base_de_datos", false, "No respondió: " + e.getMessage()));
            criticalFailure = true;
        }

        // 2. Disco: escritura y borrado de un archivo temporal invisible dentro de data/.
        try {
            Files.createDirectories(dataDir);
            Path tempFile = dataDir.resolve(".selftest_tmp");
            Files.writeString(tempFile, "selftest " + LocalDateTime.now());
            Files.deleteIfExists(tempFile);
            checks.add(new CheckResult("disco_escritura", true, "Escritura y borrado de prueba correctos"));
        } catch (Exception e) {
            checks.add(new CheckResult("disco_escritura", false, "No se pudo escribir en " + dataDir + ": " + e.getMessage()));
            criticalFailure = true;
        }

        // 3. Espacio libre en disco (los audios y la base de datos crecen con el tiempo).
        try {
            File dataDirectory = dataDir.toAbsolutePath().toFile();
            double freeSpaceGb = dataDirectory.getUsableSpace() / (1024.0 * 1024.0 * 1024.0);
            boolean ok = freeSpaceGb >= minimumFreeSpaceGb;
            checks.add(new CheckResult("espacio_libre", ok,
                    String.format("%.1f GB libres (mínimo configurado: %.1f GB)", freeSpaceGb, minimumFreeSpaceGb)));
        } catch (Exception e) {
            checks.add(new CheckResult("espacio_libre", false, "No se pudo calcular: " + e.getMessage()));
        }

        // 4. Servicios externos: solo se valida que sus claves estén configuradas.
        checks.add(new CheckResult("groq_whisper", groqConfigured,
                groqConfigured ? "Clave configurada; disponibilidad se valida al transcribir."
                        : "Falta GROQ_API_KEY; la transcripción automática no está disponible."));
        checks.add(new CheckResult("geoapify", geoapifyConfigured,
                geoapifyConfigured ? "Clave configurada; disponibilidad se valida al geocodificar."
                        : "Falta GEOAPIFY_API_KEY; la geocodificación automática no está disponible."));

        // 5. Carpeta de caché de geocodificación. Tampoco crítico.
        try {
            Path cacheDirectory = geocachePath.toAbsolutePath().getParent();
            boolean ok = cacheDirectory == null || Files.isDirectory(cacheDirectory) || cacheDirectory.toFile().mkdirs();
            checks.add(new CheckResult("geocache", ok, ok ? "Carpeta de caché accesible" : "No se pudo acceder a " + cacheDirectory));
        } catch (Exception e) {
            checks.add(new CheckResult("geocache", false, "No se pudo verificar: " + e.getMessage()));
        }

        String status = criticalFailure ? "FALLA"
                : checks.stream().anyMatch(c -> !c.ok()) ? "DEGRADADO"
                : "OK";

        SelfTestResult result = new SelfTestResult(LocalDateTime.now(), status, checks);
        latestResult.set(result);

        switch (status) {
            case "OK" -> log.debug("Autochequeo interno: OK");
            case "DEGRADADO" -> log.warn("Autochequeo interno: DEGRADADO -> {}", checks);
            default -> log.error("Autochequeo interno: FALLA -> {}", checks);
        }
        return result;
    }
}
