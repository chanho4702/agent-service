package com.platform.agentservice.office.dto;

import com.platform.agentservice.run.dto.RunSummaryResponse;

import java.math.BigDecimal;
import java.util.List;

/** {@code GET /api/agent/personas/{id}/activity} — 개인 오피스(최근 run 20·오늘 감사 50). */
public record PersonaActivityResponse(
        long personaId,
        List<RunSummaryResponse> runs,
        List<AuditEntry> todayAudits,
        BigDecimal todayCostUsd
) {
}
