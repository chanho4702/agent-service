package com.platform.agentservice.office.dto;

import com.platform.agentservice.budget.dto.BudgetStatusResponse;
import com.platform.agentservice.persona.PersonaRole;
import com.platform.agentservice.run.GateKind;
import com.platform.agentservice.run.RunStatus;
import com.platform.agentservice.run.RunTrigger;
import com.platform.agentservice.run.RunType;
import com.platform.agentservice.run.dto.RunSummaryResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** {@code GET /api/agent/office} — AI 사무실 한 화면 분량을 한 번의 폴링으로 내려준다(P3a D-P3a-1). */
public record OfficeResponse(
        List<OfficePersona> personas,
        List<RunSummaryResponse> recentRuns,
        long pendingGateCount,
        List<PendingGate> pendingGates,
        BudgetStatusResponse budget,
        Instant generatedAt
) {

    /** currentRun·lastActivity는 없으면 null — 유휴 페르소나, 5분 넘게 조용한 페르소나. */
    public record OfficePersona(
            long id,
            String slug,
            String name,
            String emoji,
            PersonaRole role,
            boolean active,
            CurrentRun currentRun,
            AuditEntry lastActivity,
            BigDecimal todayCostUsd
    ) {
    }

    public record CurrentRun(
            long id,
            RunStatus status,
            String issueKey,
            RunType type,
            RunTrigger trigger,
            int attempt,
            String model,
            Instant startedAt
    ) {
    }

    /**
     * requestSummary는 게이트 요청문 앞부분만 — 전문은 {@code GET /api/agent/gates}에 있다. 감사 summary와 달리
     * {@code AuditSummaryRedactor}를 거치지 않는 것은 의도다: 같은 요청문을 인증 사용자 누구나 이미
     * {@code GET /api/agent/gates}로 전문 조회할 수 있어, 여기서 가려도 노출 수준이 달라지지 않는다.
     */
    public record PendingGate(
            long id,
            long runId,
            String issueKey,
            long personaId,
            GateKind kind,
            String requestSummary,
            Instant requestedAt
    ) {
    }
}
