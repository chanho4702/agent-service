package com.platform.agentservice.pat;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "pat_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PatToken {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 64) private String tokenHash;
    @Column(nullable = false, length = 120) private String label;  // V1 컬럼폭 — PatCreateRequest.label @Size와 짝
    @Column(nullable = false) private Long ownerMemberId;
    @Column(nullable = false) private Long personaId;
    @CreationTimestamp @Column(nullable = false, updatable = false) private Instant createdAt;
    private Instant expiresAt;
    private Instant lastUsedAt;
    private Instant revokedAt;

    public static PatToken of(String tokenHash, String label, Long ownerMemberId, Long personaId, Instant expiresAt) {
        PatToken t = new PatToken();
        t.tokenHash = tokenHash;
        t.label = label;
        t.ownerMemberId = ownerMemberId;
        t.personaId = personaId;
        t.expiresAt = expiresAt;
        return t;
    }

    /** PatAuthFilter가 검증 성공 시 스로틀 적용 후 호출한다. */
    public void touch(Instant at) {
        this.lastUsedAt = at;
    }

    /** 멱등 — 이미 철회된 토큰에 다시 호출해도 최초 철회 시각을 유지한다. */
    public void revoke(Instant at) {
        if (this.revokedAt == null) {
            this.revokedAt = at;
        }
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && expiresAt.isBefore(now);
    }
}
