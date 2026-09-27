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

    /** P3g 수다 비용은 run 없이 적재되므로 null일 수 있다(V10). */
    private Long runId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private LedgerScope scope;
    @Column(nullable = false, length = 40) private String scopeId;
    @Column(nullable = false, precision = 10, scale = 4) private BigDecimal costUsd;
    @Column(nullable = false) private long inputTokens;
    @Column(nullable = false) private long outputTokens;
    @Column(length = 60) private String model;
    /** 어떤 층 LLM 키로 돈 run인지(P3h — PROJECT|PLATFORM|ENV|NONE). 원문·힌트는 싣지 않는다. P3h 이전 행은 null. */
    @Column(length = 10) private String credentialScope;
    @CreationTimestamp @Column(nullable = false, updatable = false) private Instant createdAt;

    public static UsageLedger of(long runId, LedgerScope scope, String scopeId, BigDecimal costUsd,
                                  long inputTokens, long outputTokens, String model) {
        return of(runId, scope, scopeId, costUsd, inputTokens, outputTokens, model, null);
    }

    public static UsageLedger of(long runId, LedgerScope scope, String scopeId, BigDecimal costUsd,
                                  long inputTokens, long outputTokens, String model, String credentialScope) {
        return build(runId, scope, scopeId, costUsd, inputTokens, outputTokens, model, credentialScope);
    }

    /** run 없는 비용(P3g 사무실 수다). */
    public static UsageLedger withoutRun(LedgerScope scope, String scopeId, BigDecimal costUsd,
                                         long inputTokens, long outputTokens, String model, String credentialScope) {
        return build(null, scope, scopeId, costUsd, inputTokens, outputTokens, model, credentialScope);
    }

    private static UsageLedger build(Long runId, LedgerScope scope, String scopeId, BigDecimal costUsd,
                                     long inputTokens, long outputTokens, String model, String credentialScope) {
        UsageLedger u = new UsageLedger();
        u.runId = runId;
        u.scope = scope;
        u.scopeId = scopeId;
        u.costUsd = costUsd;
        u.inputTokens = inputTokens;
        u.outputTokens = outputTokens;
        u.model = model;
        u.credentialScope = credentialScope;
        return u;
    }
}
