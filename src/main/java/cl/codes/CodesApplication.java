package cl.codes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import cl.codes.config.LocalDataDirectory;
import cl.codes.config.LocalServerConfiguration;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
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
        try {
            configureLocalDataDirectory();
        } catch (RuntimeException exception) {
            String safeDetail = exception instanceof IllegalStateException
                    ? exception.getMessage() : exception.getClass().getSimpleName();
            log.error("CODES local startup preparation failed: {}", safeDetail);
            showStartupError("No se pudo preparar el directorio local de CODES. Revise permisos, espacio disponible "
                    + "y que los datos estén fuera de la carpeta de instalación.");
            return;
        }
        if (Boolean.getBoolean("codes.packaged") && !GraphicsEnvironment.isHeadless()) {
            PackagedDesktopLauncher.launch(args);
            return;
        }
        startServer(args);
    }

    static ConfigurableApplicationContext startServer(String[] args) {
        if (Boolean.getBoolean("codes.packaged")) {
            cl.codes.config.LocalSecretBootstrap.loadLocalSecrets();
        }
        SpringApplication application = new SpringApplication(CodesApplication.class);
        LocalServerConfiguration.enforceLoopbackOnly(application);
        return application.run(args);
    }

    private static void configureLocalDataDirectory() {
        boolean packaged = Boolean.getBoolean("codes.packaged");
        String configuredDirectory = System.getProperty("CODES_DATA_DIR");
        if (configuredDirectory == null || configuredDirectory.isBlank()) {
            configuredDirectory = System.getenv("CODES_DATA_DIR");
        }
        if (configuredDirectory == null || configuredDirectory.isBlank()) {
            if (packaged) {
                String localAppData = System.getenv("LOCALAPPDATA");
                configuredDirectory = LocalDataDirectory.defaultPackagedDirectory(
                        localAppData, Path.of(System.getProperty("user.home"))).toString();
            } else {
                configuredDirectory = Path.of("data").toString();
            }
        }
        Path dataDirectory = LocalDataDirectory.prepare(Path.of(configuredDirectory),
                packaged ? PackagedDesktopLauncher.appImageDirectory() : null);

        String normalizedDirectory = dataDirectory.toString().replace('\\', '/');
        System.setProperty("CODES_DATA_DIR", normalizedDirectory);
        if (packaged) {
            System.setProperty("CODES_AUDIT_LOG_PATH",
                    Path.of(normalizedDirectory, "logs", "auditoria.log").toString().replace('\\', '/'));
        }
    }

    private static void showStartupError(String message) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println(message);
            return;
        }
        javax.swing.JOptionPane.showMessageDialog(null, message, "CODES - Error de inicio",
                javax.swing.JOptionPane.ERROR_MESSAGE);
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
