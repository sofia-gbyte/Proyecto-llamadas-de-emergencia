package cl.codes.config;

import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Enforces a single-machine network boundary before Spring creates its web
 * server. The server always binds to IPv4 loopback even when a loopback alias
 * was configured.
 */
public final class LocalServerConfiguration {
    public static final String LOOPBACK_ADDRESS = "127.0.0.1";
    public static final String INVALID_ADDRESS_MESSAGE =
            "CODES solo permite conexiones desde este equipo. Configure server.address como 127.0.0.1.";
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}");

    private LocalServerConfiguration() {
    }

    public static void enforceLoopbackOnly(SpringApplication application) {
        application.addInitializers(context -> {
            ConfigurableEnvironment environment = context.getEnvironment();
            String configuredAddress = environment.getProperty("server.address", LOOPBACK_ADDRESS);
            if (configuredAddress == null || configuredAddress.isBlank()) configuredAddress = LOOPBACK_ADDRESS;
            if (!isLoopbackAddress(configuredAddress)) {
                throw new IllegalStateException(INVALID_ADDRESS_MESSAGE);
            }
            environment.getPropertySources().addFirst(new MapPropertySource(
                    "codes-loopback-only", Map.of("server.address", LOOPBACK_ADDRESS)));
        });
    }

    public static boolean isLoopbackAddress(String address) {
        if (address == null || address.isBlank()) return false;
        String normalized = address.trim();
        if ("localhost".equalsIgnoreCase(normalized) || "::1".equals(normalized)
                || "[::1]".equals(normalized)) return true;
        if (!IPV4.matcher(normalized).matches()) return false;
        String[] octets = normalized.split("\\.");
        try {
            int first = Integer.parseInt(octets[0]);
            for (String octet : octets) {
                if (Integer.parseInt(octet) > 255) return false;
            }
            return first == 127;
        } catch (NumberFormatException exception) {
            return false;
        }
    }
}
