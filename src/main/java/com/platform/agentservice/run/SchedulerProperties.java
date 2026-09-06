package com.platform.agentservice.run;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 디스패처 스케줄러 설정(P2a T4) — {@code platform.agent.scheduler.*}. {@link #enabled()}
 * 기본값은 반드시 false다(킬스위치) — 무인 루프가 실수로 켜진 채 배포되지 않게 한다.
 * {@link #retryMaxAttempts()}는 {@link Run#getAttempt()}와 비교하는 상한이다(재시도
 * 포함 최대 시도 횟수 — attempt=1이 최초 시도).
 */
@ConfigurationProperties(prefix = "platform.agent.scheduler")
public record SchedulerProperties(
        boolean enabled,
        long intervalMs,
        int maxConcurrentGlobal,
        int maxConcurrentPerProject,
        String defaultPersonaSlug,
        int retryMaxAttempts
) {
}
