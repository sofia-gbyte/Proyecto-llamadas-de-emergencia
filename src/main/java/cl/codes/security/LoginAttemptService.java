package cl.codes.security;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in-memory brute-force protection for login attempts.
 */
@Service
public class LoginAttemptService {

    private static final int MAX_ATTEMPTS = 5;
    private static final long BLOCK_DURATION_MINUTES = 15;

    private record AttemptState(int failedAttempts, Instant blockedUntil) {}

    private final ConcurrentHashMap<String, AttemptState> attemptStates = new ConcurrentHashMap<>();

    public boolean isBlocked(String user) {
        AttemptState state = attemptStates.get(user.toLowerCase());
        return state != null && state.blockedUntil() != null && Instant.now().isBefore(state.blockedUntil());
    }

    public long remainingBlockMinutes(String user) {
        AttemptState state = attemptStates.get(user.toLowerCase());
        if (state == null || state.blockedUntil() == null) return 0;
        long seconds = Instant.now().until(state.blockedUntil(), java.time.temporal.ChronoUnit.SECONDS);
        return Math.max(0, (seconds + 59) / 60);
    }

    public void registerFailure(String user) {
        String key = user.toLowerCase();
        attemptStates.compute(key, (k, current) -> {
            int attempts = (current == null ? 0 : current.failedAttempts()) + 1;
            Instant blockUntil = attempts >= MAX_ATTEMPTS
                    ? Instant.now().plusSeconds(BLOCK_DURATION_MINUTES * 60)
                    : null;
            return new AttemptState(attempts, blockUntil);
        });
    }

    public void registerSuccess(String user) {
        attemptStates.remove(user.toLowerCase());
    }
}
