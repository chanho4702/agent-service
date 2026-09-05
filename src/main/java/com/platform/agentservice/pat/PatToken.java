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
    @Column(nullable = false) private String label;
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
}
