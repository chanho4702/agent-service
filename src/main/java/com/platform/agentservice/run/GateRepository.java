package com.platform.agentservice.run;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GateRepository extends JpaRepository<Gate, Long> {
    List<Gate> findByDecisionIsNull();

    /** {@code GET /api/agent/gates}(pending 미지정/false) — 요청순 최신 50건. */
    List<Gate> findTop50ByOrderByRequestedAtDesc();
}
