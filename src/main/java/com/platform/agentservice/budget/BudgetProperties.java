package com.platform.agentservice.budget;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * 예산 캡 설정(P2a T5) — {@code platform.agent.budget.*}. 둘 다 USD, 문자열로 바인딩해도
 * {@link BigDecimal}로 relaxed binding된다. {@link #monthlyUsdCap()}은 캘린더 월(UTC 기준)
 * 누적 한도 — 플랫폼 전체와 프로젝트별에 P2a에서는 같은 값을 쓴다(프로젝트별 개별 한도는
 * P2b). {@link #perRunUsdCap()}은 run 한 건이 넘으면 안 되는 한도 — 넘어도 run을 되돌리지
 * 않고 경고 코멘트만 남긴다({@code RunService} 참고).
 */
@ConfigurationProperties(prefix = "platform.agent.budget")
public record BudgetProperties(
        BigDecimal monthlyUsdCap,
        BigDecimal perRunUsdCap
) {
}
