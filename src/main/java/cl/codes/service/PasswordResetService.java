package cl.codes.service;

import cl.codes.model.PasswordResetToken;
import cl.codes.model.User;
import cl.codes.repository.PasswordResetTokenRepository;
import cl.codes.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;

@Service
public class PasswordResetService {
    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JavaMailSender mailSender;
    private final SecureRandom random = new SecureRandom();
    private final String publicBaseUrl;
    private final String fromAddress;

    public PasswordResetService(UserRepository userRepository, PasswordResetTokenRepository tokenRepository,
                                PasswordEncoder passwordEncoder, JavaMailSender mailSender,
                                @Value("${app.public-base-url:http://localhost:8000}") String publicBaseUrl,
                                @Value("${app.mail-from:${spring.mail.username:}}") String fromAddress) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.mailSender = mailSender;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/$", "");
        this.fromAddress = fromAddress;
    }

    @Transactional
    public void requestReset(String email) {
        tokenRepository.deleteByExpiresAtBefore(LocalDateTime.now());
        User user = userRepository.findByEmailIgnoreCase(email.strip()).orElse(null);
        if (user == null || !user.isActive() || user.getEmail() == null) return;

        tokenRepository.deleteByUserId(user.getId());
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String rawToken = HexFormat.of().formatHex(bytes);

        PasswordResetToken token = new PasswordResetToken();
        token.setTokenHash(hash(rawToken));
        token.setUser(user);
        token.setExpiresAt(LocalDateTime.now().plusMinutes(15));
        tokenRepository.save(token);

        String link = publicBaseUrl + "/?resetToken=" + rawToken;
        SimpleMailMessage mail = new SimpleMailMessage();
        if (!fromAddress.isBlank()) mail.setFrom(fromAddress);
        mail.setTo(user.getEmail());
        mail.setSubject("CODES · Recuperación de contraseña");
        mail.setText("Hola " + (user.getFirstName() == null ? user.getUsername() : user.getFirstName()) + ",\n\n" +
                "Recibimos una solicitud para restablecer tu contraseña de CODES.\n\n" +
                "Usa este enlace dentro de los próximos 15 minutos:\n" + link + "\n\n" +
                "El enlace es de un solo uso. Si no solicitaste este cambio, puedes ignorar este correo.\n\n" +
                "CODES · Consola operativa");
        mailSender.send(mail);
    }

    @Transactional
    public boolean resetPassword(String rawToken, String newPassword) {
        if (rawToken == null || rawToken.length() != 64) return false;
        PasswordResetToken token = tokenRepository.findByTokenHash(hash(rawToken)).orElse(null);
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

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("No se pudo generar el hash del token", e); }
    }
}
