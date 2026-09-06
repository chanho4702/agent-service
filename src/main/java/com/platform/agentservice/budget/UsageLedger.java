package com.platform.agentservice.budget;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/** Run 하나가 소모한 비용/토큰 한 건 — 스펙 D9·§10.5. 프로젝트/플랫폼 예산 집계의 원장. */
@Entity
@Table(name = "usage_ledger")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UsageLedger {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false) private Long runId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private LedgerScope scope;
    @Column(nullable = false, length = 40) private String scopeId;
    @Column(nullable = false, precision = 10, scale = 4) private BigDecimal costUsd;
    @Column(nullable = false) private long inputTokens;
    @Column(nullable = false) private long outputTokens;
    @Column(length = 60) private String model;
    @CreationTimestamp @Column(nullable = false, updatable = false) private Instant createdAt;

    public static UsageLedger of(long runId, LedgerScope scope, String scopeId, BigDecimal costUsd,
                                  long inputTokens, long outputTokens, String model) {
        UsageLedger u = new UsageLedger();
        u.runId = runId;
        u.scope = scope;
        u.scopeId = scopeId;
        u.costUsd = costUsd;
        u.inputTokens = inputTokens;
        u.outputTokens = outputTokens;
        u.model = model;
        return u;
    }
}
