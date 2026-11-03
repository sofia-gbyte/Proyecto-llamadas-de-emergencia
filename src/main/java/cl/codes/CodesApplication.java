package cl.codes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

@SpringBootApplication
@EnableScheduling // habilita @Scheduled: autochequeo interno y limpieza de tokens vencidos
public class CodesApplication {
    private static final Logger log = LoggerFactory.getLogger(CodesApplication.class);

    public static void main(String[] args) {
        configurePackagedDataDirectory();
        if (Boolean.getBoolean("codes.packaged")) {
            cl.codes.config.LocalSecretBootstrap.loadLocalSecrets();
        }
        SpringApplication.run(CodesApplication.class, args);
    }

    private static void configurePackagedDataDirectory() {
        if (!Boolean.getBoolean("codes.packaged")) return;
        String configuredDirectory = System.getProperty("CODES_DATA_DIR");
        if (configuredDirectory == null || configuredDirectory.isBlank()) {
            configuredDirectory = System.getenv("CODES_DATA_DIR");
        }
        if (configuredDirectory == null || configuredDirectory.isBlank()) {
            String localAppData = System.getenv("LOCALAPPDATA");
            Path baseDirectory = localAppData == null || localAppData.isBlank()
                    ? Path.of(System.getProperty("user.home"), ".codes")
                    : Path.of(localAppData, "CODES");
            configuredDirectory = baseDirectory.resolve("data").toString();
        }
        String normalizedDirectory = Path.of(configuredDirectory).toAbsolutePath().normalize().toString().replace('\\', '/');
        System.setProperty("CODES_DATA_DIR", normalizedDirectory);
        System.setProperty("CODES_AUDIT_LOG_PATH",
                Path.of(normalizedDirectory, "logs", "auditoria.log").toString().replace('\\', '/'));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void openPackagedBrowser(org.springframework.boot.context.event.ApplicationReadyEvent event) {
        if (!Boolean.getBoolean("codes.packaged")
                || !event.getApplicationContext().getEnvironment()
                .getProperty("codes.open-browser", Boolean.class, true)) return;
        int port = event.getApplicationContext().getEnvironment().getProperty("server.port", Integer.class, 8000);
        URI applicationUri = URI.create("http://127.0.0.1:" + port);
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                try {
                    Desktop.getDesktop().browse(applicationUri);
                    log.info("Opened CODES in the default browser.");
                    return;
                } catch (IOException | UnsupportedOperationException e) {
                    log.debug("Desktop browser launch failed; trying the operating system handler.", e);
                }
            }
            openWithSystemHandler(applicationUri);
            log.info("Opened CODES using the operating system browser handler.");
        } catch (Exception e) {
            log.warn("CODES is running, but the browser could not be opened automatically.", e);
        }
    }

    static List<String> systemBrowserCommand(URI applicationUri, String osName) {
        String operatingSystem = osName.toLowerCase(Locale.ROOT);
        if (operatingSystem.startsWith("windows")) {
            return List.of("rundll32.exe", "url.dll,FileProtocolHandler", applicationUri.toString());
        }
        if (operatingSystem.contains("mac")) return List.of("open", applicationUri.toString());
        if (operatingSystem.contains("linux")) return List.of("xdg-open", applicationUri.toString());
        throw new IllegalStateException("Unsupported operating system browser handler: " + osName);
    }

    private static void openWithSystemHandler(URI applicationUri) throws IOException {
        new ProcessBuilder(systemBrowserCommand(applicationUri, System.getProperty("os.name", ""))).start();
    }
}
