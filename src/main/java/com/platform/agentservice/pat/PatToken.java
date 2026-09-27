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
    /** run 토큰(워커용 임시 PAT) 식별 규약 — 발급자 센티널 + label 접두. {@code RunTokenService}가 이 값으로 발급한다. */
    public static final long SYSTEM_OWNER_MEMBER_ID = 0L;
    public static final String RUN_LABEL_PREFIX = "run:";

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

    /**
     * 시스템이 run에 묶어 발급한 토큰인가. 발급자 센티널까지 같이 보는 이유: 관리자가 label을
     * "run:"으로 지어 발급한 사람용 PAT이 run 토큰 취급(비활성 페르소나 예외)을 받으면 안 된다.
     */
    public boolean isRunToken() {
        return ownerMemberId != null && ownerMemberId == SYSTEM_OWNER_MEMBER_ID
                && label != null && label.startsWith(RUN_LABEL_PREFIX);
    }

    /** run 토큰이면 label {@code run:<id>}의 run id, 아니거나 해석 불가면 null. */
    public Long runIdFromLabel() {
        if (!isRunToken()) {
            return null;
        }
        try {
            return Long.valueOf(label.substring(RUN_LABEL_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && expiresAt.isBefore(now);
    }
}
