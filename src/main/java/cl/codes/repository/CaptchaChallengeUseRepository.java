package cl.codes.repository;

import cl.codes.model.CaptchaChallengeUse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

public interface CaptchaChallengeUseRepository extends JpaRepository<CaptchaChallengeUse, Long> {
    @Transactional
    void deleteByExpiresAtBefore(LocalDateTime expirationTime);
}
