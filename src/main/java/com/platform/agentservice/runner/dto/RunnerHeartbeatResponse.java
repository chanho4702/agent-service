package com.platform.agentservice.runner.dto;

import com.platform.agentservice.runner.RunnerKind;

import java.time.Instant;
import java.util.List;

/**
 * heartbeat 응답. {@code stopRunIds}: 러너가 돌린다고 보고했지만 서버 쪽에서 이미 취소·차단됐거나 이 러너 배정이 아닌 run — 러너는
 * 그 워커 프로세스를 멈춘다(결과 보고는 해도 되고 안 해도 된다). {@code offlineAfterSeconds}: 이만큼 heartbeat가 끊기면 서버가 그 러너의
 * RUNNING run을 사고형 BLOCKED로 돌린다.
 */
public record RunnerHeartbeatResponse(long runnerId, RunnerKind kind, Instant serverTime, List<Long> stopRunIds,
                                      long offlineAfterSeconds) {
}
