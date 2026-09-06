package com.platform.agentservice.run;

import com.platform.common.error.ConflictException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/** Run 하나에 걸리는 승인 게이트 — 스펙 D9. 결정은 1회만 유효(멱등 아님 — 재결정은 거부). */
@Entity
@Table(name = "gate")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Gate {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false) private Long runId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private GateKind kind;
    @Column(nullable = false, columnDefinition = "text") private String request;
    private Long decidedBy;
    @Enumerated(EnumType.STRING) @Column(length = 10) private GateDecision decision;
    @CreationTimestamp @Column(nullable = false, updatable = false) private Instant requestedAt;
    private Instant decidedAt;

    public static Gate request(long runId, GateKind kind, String request) {
        Gate g = new Gate();
        g.runId = runId;
        g.kind = kind;
        g.request = request;
        return g;
    }

    public void approve(long deciderId) {
        requireUndecided();
        this.decision = GateDecision.APPROVE;
        this.decidedBy = deciderId;
        this.decidedAt = Instant.now();
    }

    public void reject(long deciderId) {
        requireUndecided();
        this.decision = GateDecision.REJECT;
        this.decidedBy = deciderId;
        this.decidedAt = Instant.now();
    }

    private void requireUndecided() {
        if (this.decision != null) throw new ConflictException("이미 결정된 게이트입니다: " + this.decision);
    }
}
