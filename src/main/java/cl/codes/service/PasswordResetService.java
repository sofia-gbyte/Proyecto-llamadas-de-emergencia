package cl.codes.service;

import cl.codes.model.PasswordResetToken;
import cl.codes.model.User;
import cl.codes.repository.PasswordResetTokenRepository;
import cl.codes.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;

import cl.codes.security.TokenHashing;

@Service
public class PasswordResetService {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final CodesMailService mailService;
    private final SecureRandom random = new SecureRandom();
    private final String publicBaseUrl;
    private final boolean externalServicesEnabled;

    public PasswordResetService(UserRepository userRepository, PasswordResetTokenRepository tokenRepository,
                                PasswordEncoder passwordEncoder, CodesMailService mailService,
                                @Value("${app.public-base-url:http://localhost:8000}") String publicBaseUrl,
                                @Value("${app.external-services.enabled:false}") boolean externalServicesEnabled) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.mailService = mailService;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/$", "");
        this.externalServicesEnabled = externalServicesEnabled;
    }

    @Transactional
    public void requestReset(String email) {
        if (!externalServicesEnabled) {
            log.info("Password recovery email skipped because external providers are disabled.");
            return;
        }
        tokenRepository.deleteByExpiresAtBefore(LocalDateTime.now());
        User user = userRepository.findByEmailIgnoreCase(email.strip()).orElse(null);
        if (user == null || !user.isActive() || !user.isEmailVerified() || user.getEmail() == null) return;

        tokenRepository.deleteByUserId(user.getId());
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String rawToken = HexFormat.of().formatHex(bytes);

        PasswordResetToken token = new PasswordResetToken();
        token.setTokenHash(TokenHashing.sha256(rawToken));
        token.setUser(user);
        token.setExpiresAt(LocalDateTime.now().plusMinutes(15));
        tokenRepository.save(token);

        String link = publicBaseUrl + "/?resetToken=" + rawToken;
        mailService.sendPasswordResetEmail(user, link);
    }

    @Transactional
    public boolean resetPassword(String rawToken, String newPassword) {
        if (rawToken == null || rawToken.length() != 64) return false;
        PasswordResetToken token = tokenRepository.findByTokenHash(TokenHashing.sha256(rawToken)).orElse(null);
        if (token == null || !token.isValid()) return false;
        User user = token.getUser();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        // Cualquier sesión abierta con la contraseña anterior queda inválida.
        user.setSessionsValidFrom(LocalDateTime.now());
        userRepository.save(user);
        token.setUsedAt(LocalDateTime.now());
        tokenRepository.save(token);
        return true;
    }
}
