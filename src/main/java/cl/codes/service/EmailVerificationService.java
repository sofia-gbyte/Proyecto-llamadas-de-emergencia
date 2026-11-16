package cl.codes.service;

import cl.codes.exception.EmailDeliveryException;
import cl.codes.model.EmailVerificationToken;
import cl.codes.model.User;
import cl.codes.repository.EmailVerificationTokenRepository;
import cl.codes.repository.UserRepository;
import cl.codes.security.TokenHashing;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;

@Service
public class EmailVerificationService {
    private static final int TOKEN_BYTES = 32;
    private static final int TOKEN_VALIDITY_HOURS = 24;

    private final UserRepository userRepository;
    private final EmailVerificationTokenRepository tokenRepository;
    private final CodesMailService mailService;
    private final SecureRandom random = new SecureRandom();
    private final String publicBaseUrl;

    public EmailVerificationService(
            UserRepository userRepository,
            EmailVerificationTokenRepository tokenRepository,
            CodesMailService mailService,
            @Value("${app.public-base-url:http://localhost:8000}") String publicBaseUrl
    ) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.mailService = mailService;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/$", "");
    }

    @Transactional
    public void sendVerification(User user) {
        if (!mailService.isEnabled()) {
            throw new EmailDeliveryException("Activa el envío de servicios externos para confirmar el correo.");
        }
        issueTokenAndSend(user);
    }

    @Transactional
    public void resend(String email) {
        if (!mailService.isEnabled()) {
            throw new EmailDeliveryException("Activa el envío de servicios externos para confirmar el correo.");
        }
        tokenRepository.deleteByExpiresAtBefore(LocalDateTime.now());
        User user = userRepository.findByEmailIgnoreCase(email.strip()).orElse(null);
        if (user == null || user.isEmailVerified() || user.getEmail() == null) return;
        issueTokenAndSend(user);
    }

    @Transactional
    public boolean verify(String rawToken) {
        if (rawToken == null || !rawToken.matches("(?i)[a-f0-9]{64}")) return false;
        EmailVerificationToken token = tokenRepository.findByTokenHash(TokenHashing.sha256(rawToken))
                .orElse(null);
        if (token == null || !token.isValid()) return false;

        User user = token.getUser();
        if (user.getEmail() == null || !token.getEmailAddress().equalsIgnoreCase(user.getEmail())) return false;

        user.setEmailVerified(true);
        userRepository.save(user);
        token.setUsedAt(LocalDateTime.now());
        tokenRepository.save(token);
        return true;
    }

    private void issueTokenAndSend(User user) {
        tokenRepository.deleteByExpiresAtBefore(LocalDateTime.now());
        tokenRepository.deleteByUserId(user.getId());

        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String rawToken = HexFormat.of().formatHex(bytes);

        EmailVerificationToken token = new EmailVerificationToken();
        token.setTokenHash(TokenHashing.sha256(rawToken));
        token.setUser(user);
        token.setEmailAddress(user.getEmail());
        token.setExpiresAt(LocalDateTime.now().plusHours(TOKEN_VALIDITY_HOURS));
        tokenRepository.save(token);

        mailService.sendVerificationEmail(user, publicBaseUrl + "/?verifyToken=" + rawToken);
    }
}
