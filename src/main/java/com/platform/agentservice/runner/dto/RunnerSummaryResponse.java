package com.platform.agentservice.runner.dto;

import com.platform.agentservice.runner.RunnerKind;
import com.platform.agentservice.runner.RunnerStatus;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /api/agent/runners} 항목 — 해시·원문 토큰은 없다. {@code status}는 heartbeat로 파생(ONLINE·OFFLINE·NEVER_CONNECTED·REVOKED),
 * {@code currentRunIds}는 이 러너에 배정돼 RUNNING인 run.
 */
public record RunnerSummaryResponse(
        long id,
        RunnerKind kind,
        String name,
        Long projectId,
        long issuedBy,
        String tokenPrefix,
        Instant createdAt,
        Instant revokedAt,
        Instant lastHeartbeatAt,
        String version,
        String os,
        int maxConcurrency,
        RunnerStatus status,
        List<Long> currentRunIds
) {
}
