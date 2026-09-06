package com.platform.agentservice.run.dto;

import com.platform.agentservice.run.Run;
import com.platform.agentservice.run.RunStatus;

import java.time.Instant;

/** {@code GET /api/agent/runs} 감독 API 응답 — 최소 필드만(P2a T4). */
public record RunSummaryResponse(
        long id,
        String issueKey,
        RunStatus status,
        long personaId,
        int attempt,
        String model,
        Instant startedAt,
        Instant endedAt
) {
    public static RunSummaryResponse of(Run run) {
        return new RunSummaryResponse(run.getId(), run.getIssueKey(), run.getStatus(), run.getPersonaId(),
                run.getAttempt(), run.getModel(), run.getStartedAt(), run.getEndedAt());
    }
}
