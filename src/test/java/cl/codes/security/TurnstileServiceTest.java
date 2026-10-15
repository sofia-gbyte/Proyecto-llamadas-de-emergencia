package cl.codes.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnstileServiceTest {

    @Test
    void disabledTurnstileDoesNotRequireAnExternalToken() {
        TurnstileService service = new TurnstileService("", "", false);

        assertTrue(service.verify(null, "127.0.0.1"));
    }

    @Test
    void enabledTurnstileRejectsMissingToken() {
        TurnstileService service = new TurnstileService("secret", "site", true);

        assertFalse(service.verify("", "127.0.0.1"));
    }
}
