package cl.codes.security;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limita automatizaciones contra los endpoints públicos de autenticación.
 * Es deliberadamente local: para varias instancias debe sustituirse por un
 * almacén compartido como Redis.
 */
@Service
public class AuthRateLimitService {

    private static final int MAX_TRACKED_CLIENTS = 10_000;
    private static final Window LOGIN_WINDOW = new Window(10, Duration.ofMinutes(15));
    private static final Window REGISTER_WINDOW = new Window(3, Duration.ofHours(1));

    private record Window(int maxRequests, Duration duration) {}
    private record Counter(Instant startedAt, int requests) {}

    private final ConcurrentHashMap<String, Counter> loginCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> registerCounters = new ConcurrentHashMap<>();

    public boolean allowLogin(String clientIp) {
        return allow(loginCounters, clientIp, LOGIN_WINDOW);
    }

    public boolean allowRegister(String clientIp) {
        return allow(registerCounters, clientIp, REGISTER_WINDOW);
    }

    private boolean allow(ConcurrentHashMap<String, Counter> counters, String clientIp, Window window) {
        String key = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp;
        Instant now = Instant.now();
        Counter[] result = new Counter[1];
        counters.compute(key, (ignored, current) -> {
            if (current == null || current.startedAt().plus(window.duration()).isBefore(now)) {
                result[0] = new Counter(now, 1);
                return result[0];
            }
            result[0] = new Counter(current.startedAt(), current.requests() + 1);
            return result[0];
        });
        if (counters.size() > MAX_TRACKED_CLIENTS) {
            counters.remove(key, result[0]);
            return false;
        }
        return result[0].requests() <= window.maxRequests();
    }
}
