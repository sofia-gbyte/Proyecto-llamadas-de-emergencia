package cl.codes.service;

import cl.codes.repository.PasswordResetTokenRepository;
import cl.codes.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PasswordResetServiceTest {
    @Test
    void doesNotLookUpAccountsOrSendMailWhenExternalServicesAreDisabled() {
        UserRepository users = mock(UserRepository.class);
        PasswordResetTokenRepository tokens = mock(PasswordResetTokenRepository.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        PasswordResetService service = new PasswordResetService(
                users,
                tokens,
                mock(PasswordEncoder.class),
                mailSender,
                "http://localhost:8000",
                "",
                false
        );

        service.requestReset("user@example.test");

        verifyNoInteractions(users, tokens, mailSender);
    }
}
