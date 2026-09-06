package com.platform.agentservice.run.dto;

import com.platform.agentservice.run.Gate;
import com.platform.agentservice.run.GateDecision;
import com.platform.agentservice.run.GateKind;

import java.time.Instant;

/** {@code GET /api/agent/gates} 감독 API 응답 — run의 issueKey를 곁들여 화면에서 바로 식별 가능하게 한다(P2a T5). */
public record GateSummaryResponse(
        long id,
        long runId,
        String issueKey,
        GateKind kind,
        String request,
        GateDecision decision,
        Instant requestedAt
) {
    public static GateSummaryResponse of(Gate gate, String issueKey) {
        return new GateSummaryResponse(gate.getId(), gate.getRunId(), issueKey, gate.getKind(), gate.getRequest(),
                gate.getDecision(), gate.getRequestedAt());
    }
}
