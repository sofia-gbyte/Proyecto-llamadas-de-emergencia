package cl.codes.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "captcha_challenge_uses",
        indexes = @Index(name = "idx_captcha_challenge_expiry", columnList = "expires_at"),
        uniqueConstraints = @UniqueConstraint(
                name = "uk_captcha_challenge_nonce_hash",
                columnNames = "nonce_hash"
        )
)
public class CaptchaChallengeUse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nonce_hash", length = 64, nullable = false)
    private String nonceHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    protected CaptchaChallengeUse() {
    }

    public CaptchaChallengeUse(String nonceHash, LocalDateTime expiresAt) {
        this.nonceHash = nonceHash;
        this.expiresAt = expiresAt;
    }

    public Long getId() {
        return id;
    }

    public String getNonceHash() {
        return nonceHash;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }
}
