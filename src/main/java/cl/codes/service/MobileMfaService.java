package cl.codes.service;

import cl.codes.model.User;
import cl.codes.repository.UserRepository;
import cl.codes.security.MobileTotpService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
public class MobileMfaService {
    private final UserRepository userRepository;
    private final MobileTotpService totpService;

    public MobileMfaService(UserRepository userRepository, MobileTotpService totpService) {
        this.userRepository = userRepository;
        this.totpService = totpService;
    }

    @Transactional
    public Optional<Setup> beginEnrollment(String username) {
        User user = userRepository.findByUsernameForUpdate(username)
                .orElseThrow(() -> new IllegalStateException("No se encontró la cuenta de operador."));
        if (user.isMobileTotpEnabled()) return Optional.empty();

        MobileTotpService.Enrollment enrollment = totpService.createEnrollment(username);
        user.setMobileTotpSecret(enrollment.encryptedSecret());
        user.setMobileTotpLastAcceptedStep(null);
        userRepository.save(user);
        return Optional.of(new Setup(enrollment.secret(), enrollment.otpauthUri()));
    }

    @Transactional
    public boolean completeEnrollment(String username, String code) {
        User user = userRepository.findByUsernameForUpdate(username)
                .orElseThrow(() -> new IllegalStateException("No se encontró la cuenta de operador."));
        if (user.isMobileTotpEnabled() || user.getMobileTotpSecret() == null) return false;

        Long acceptedStep = totpService.matchingStep(
                user.getMobileTotpSecret(), code, user.getMobileTotpLastAcceptedStep(), Instant.now());
        if (acceptedStep == null) return false;

        user.setMobileTotpEnabled(true);
        user.setMobileTotpLastAcceptedStep(acceptedStep);
        userRepository.save(user);
        return true;
    }

    @Transactional
    public boolean consumeLoginCode(String username, String code) {
        User user = userRepository.findByUsernameForUpdate(username)
                .orElseThrow(() -> new IllegalStateException("No se encontró la cuenta de operador."));
        if (!user.isMobileTotpEnabled()) return false;

        Long acceptedStep = totpService.matchingStep(
                user.getMobileTotpSecret(), code, user.getMobileTotpLastAcceptedStep(), Instant.now());
        if (acceptedStep == null) return false;

        user.setMobileTotpLastAcceptedStep(acceptedStep);
        userRepository.save(user);
        return true;
    }

    public record Setup(String secret, String otpauthUri) {}
}
