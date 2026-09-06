package com.platform.agentservice.budget.dto;

import java.math.BigDecimal;

/** {@code GET /api/agent/budget} 응답 — 감독 스냅샷(P2a T5). 알림 연동은 P3. */
public record BudgetStatusResponse(
        BigDecimal monthlyCapUsd,
        BigDecimal platformMonthToDateUsd,
        boolean killSwitch
) {
}
