package cl.codes.service;

import cl.codes.model.User;
import cl.codes.repository.UserRepository;
import cl.codes.security.MobileTotpService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class MobileMfaServiceTest {
    @Test
    void enrollmentStoresOnlyEncryptedSecretUntilTheFirstCodeIsVerified() {
        UserRepository userRepository = mock(UserRepository.class);
        MobileTotpService totpService = mock(MobileTotpService.class);
        MobileMfaService service = new MobileMfaService(userRepository, totpService);
        User user = new User();
        user.setUsername("operador");
        when(userRepository.findByUsernameForUpdate("operador")).thenReturn(Optional.of(user));
        when(totpService.createEnrollment("operador"))
                .thenReturn(new MobileTotpService.Enrollment("VISIBLE-SECRET", "ENCRYPTED-SECRET", "otpauth://"));
        when(totpService.matchingStep(eq("ENCRYPTED-SECRET"), eq("123456"), isNull(), any()))
                .thenReturn(100L);

        var setup = service.beginEnrollment("operador").orElseThrow();

        assertEquals("VISIBLE-SECRET", setup.secret());
        assertEquals("ENCRYPTED-SECRET", user.getMobileTotpSecret());
        assertFalse(user.isMobileTotpEnabled());
        assertTrue(service.completeEnrollment("operador", "123456"));
        assertTrue(user.isMobileTotpEnabled());
        assertEquals(100L, user.getMobileTotpLastAcceptedStep());
        verify(userRepository, times(2)).save(user);
    }

    @Test
    void loginCodeIsConsumedOnceForAnEnrolledOperator() {
        UserRepository userRepository = mock(UserRepository.class);
        MobileTotpService totpService = mock(MobileTotpService.class);
        MobileMfaService service = new MobileMfaService(userRepository, totpService);
        User user = new User();
        user.setUsername("operador");
        user.setMobileTotpSecret("ENCRYPTED-SECRET");
        user.setMobileTotpEnabled(true);
        user.setMobileTotpLastAcceptedStep(99L);
        when(userRepository.findByUsernameForUpdate("operador")).thenReturn(Optional.of(user));
        when(totpService.matchingStep(
                eq("ENCRYPTED-SECRET"), eq("123456"), eq(99L), any(java.time.Instant.class)))
                .thenReturn(100L);

        assertTrue(service.consumeLoginCode("operador", "123456"));

        assertEquals(100L, user.getMobileTotpLastAcceptedStep());
        verify(userRepository).save(user);
    }
}
