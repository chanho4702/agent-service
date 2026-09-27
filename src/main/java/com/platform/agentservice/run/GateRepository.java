package com.platform.agentservice.run;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GateRepository extends JpaRepository<Gate, Long> {
    List<Gate> findByDecisionIsNull();

    /** {@code GET /api/agent/gates}(pending 미지정/false) — 요청순 최신 50건. */
    List<Gate> findTop50ByOrderByRequestedAtDesc();

    /** 회의 run의 PLAN 게이트 승인 이어받기(P3b) — 조상 run에 승인된 계획이 있는지. */
    List<Gate> findByRunIdAndKindAndDecisionOrderByIdAsc(long runId, GateKind kind, GateDecision decision);
}
