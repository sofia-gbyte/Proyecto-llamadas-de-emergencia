package cl.codes.service;

import cl.codes.exception.EmailDeliveryException;
import cl.codes.model.EmailVerificationToken;
import cl.codes.model.User;
import cl.codes.repository.EmailVerificationTokenRepository;
import cl.codes.repository.UserRepository;
import cl.codes.security.TokenHashing;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class EmailVerificationServiceTest {
    private static final String RAW_TOKEN = "a".repeat(64);

    @Test
    void sendsAHashedExpiringTokenAndVerificationDoesNotApproveTheAccount() {
        User user = pendingUser();
        UserRepository users = mock(UserRepository.class);
        EmailVerificationTokenRepository tokens = mock(EmailVerificationTokenRepository.class);
        CodesMailService mailService = mock(CodesMailService.class);
        when(mailService.isEnabled()).thenReturn(true);
        when(tokens.save(any(EmailVerificationToken.class))).thenAnswer(invocation -> invocation.getArgument(0));
        EmailVerificationService service = new EmailVerificationService(
                users, tokens, mailService, "http://localhost:8000");

        service.sendVerification(user);

        var tokenCaptor = org.mockito.ArgumentCaptor.forClass(EmailVerificationToken.class);
        verify(tokens).save(tokenCaptor.capture());
        EmailVerificationToken storedToken = tokenCaptor.getValue();
        assertNotEquals(RAW_TOKEN, storedToken.getTokenHash());
        assertEquals(user.getEmail(), storedToken.getEmailAddress());
        assertTrue(storedToken.getExpiresAt().isAfter(LocalDateTime.now()));

        var linkCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mailService).sendVerificationEmail(eq(user), linkCaptor.capture());
        Matcher matcher = Pattern.compile("verifyToken=([a-f0-9]{64})").matcher(linkCaptor.getValue());
        assertTrue(matcher.find());
        String rawToken = matcher.group(1);
        when(tokens.findByTokenHash(TokenHashing.sha256(rawToken))).thenReturn(Optional.of(storedToken));

        assertTrue(service.verify(rawToken));
        assertTrue(user.isEmailVerified());
        assertFalse(user.isActive());
        assertNotNull(storedToken.getUsedAt());
        verify(users).save(user);
        assertFalse(service.verify(rawToken));
    }

    @Test
    void rejectsExpiredTokensAndTokensForAnAddressThatChanged() {
        User user = pendingUser();
        EmailVerificationToken token = tokenFor(user, RAW_TOKEN);
        UserRepository users = mock(UserRepository.class);
        EmailVerificationTokenRepository tokens = mock(EmailVerificationTokenRepository.class);
        CodesMailService mailService = mock(CodesMailService.class);
        when(tokens.findByTokenHash(TokenHashing.sha256(RAW_TOKEN))).thenReturn(Optional.of(token));
        EmailVerificationService service = new EmailVerificationService(
                users, tokens, mailService, "http://localhost:8000");

        token.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        assertFalse(service.verify(RAW_TOKEN));

        token.setExpiresAt(LocalDateTime.now().plusHours(1));
        user.setEmail("changed@example.test");
        assertFalse(service.verify(RAW_TOKEN));
        assertFalse(user.isEmailVerified());
        verifyNoInteractions(users);
    }

    @Test
    void doesNotSendOrIssueTokensWhenExternalServicesAreDisabled() {
        UserRepository users = mock(UserRepository.class);
        EmailVerificationTokenRepository tokens = mock(EmailVerificationTokenRepository.class);
        CodesMailService mailService = mock(CodesMailService.class);
        when(mailService.isEnabled()).thenReturn(false);
        EmailVerificationService service = new EmailVerificationService(
                users, tokens, mailService, "http://localhost:8000");

        assertThrows(EmailDeliveryException.class, () -> service.sendVerification(pendingUser()));
        verifyNoInteractions(tokens);
        verify(mailService, never()).sendVerificationEmail(any(), anyString());
    }

    private static User pendingUser() {
        User user = new User();
        user.setId(42L);
        user.setUsername("operador");
        user.setEmail("operator@example.test");
        user.setActive(false);
        user.setEmailVerified(false);
        return user;
    }

    private static EmailVerificationToken tokenFor(User user, String rawToken) {
        EmailVerificationToken token = new EmailVerificationToken();
        token.setTokenHash(TokenHashing.sha256(rawToken));
        token.setUser(user);
        token.setEmailAddress(user.getEmail());
        token.setExpiresAt(LocalDateTime.now().plusHours(1));
        return token;
    }
}
