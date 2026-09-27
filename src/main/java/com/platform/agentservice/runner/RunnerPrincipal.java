package com.platform.agentservice.runner;

/** {@link RunnerAuthFilter}가 {@code agr_} 검증에 성공하면 심는 principal — 러너 프로토콜 엔드포인트만 받는다. */
public record RunnerPrincipal(long runnerId, RunnerKind kind, Long projectId) {
}
