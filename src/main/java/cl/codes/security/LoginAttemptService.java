package cl.codes.security;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in-memory brute-force protection for login attempts.
 */
@Service
public class LoginAttemptService {

    private static final int MAX_INTENTOS = 5;
    private static final long BLOQUEO_MINUTOS = 15;

    private record Estado(int intentosFallidos, Instant bloqueadoHasta) {}

    private final ConcurrentHashMap<String, Estado> estados = new ConcurrentHashMap<>();

    public boolean isBlocked(String user) {
        Estado state = estados.get(user.toLowerCase());
        return state != null && state.bloqueadoHasta() != null && Instant.now().isBefore(state.bloqueadoHasta());
    }

    public long remainingBlockMinutes(String user) {
        Estado state = estados.get(user.toLowerCase());
        if (state == null || state.bloqueadoHasta() == null) return 0;
        long seconds = Instant.now().until(state.bloqueadoHasta(), java.time.temporal.ChronoUnit.SECONDS);
        return Math.max(0, (seconds + 59) / 60);
    }

    public void registerFailure(String user) {
        String key = user.toLowerCase();
        estados.compute(key, (k, current) -> {
            int attempts = (current == null ? 0 : current.intentosFallidos()) + 1;
            Instant blockUntil = attempts >= MAX_INTENTOS
                    ? Instant.now().plusSeconds(BLOQUEO_MINUTOS * 60)
                    : null;
            return new Estado(attempts, blockUntil);
        });
    }

    public void registerSuccess(String user) {
        estados.remove(user.toLowerCase());
    }
}


