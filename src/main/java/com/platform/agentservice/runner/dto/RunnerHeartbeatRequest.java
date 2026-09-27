package com.platform.agentservice.runner.dto;

import java.util.List;

/**
 * {@code POST /api/agent/runners/heartbeat}(30초 주기 권장) — 러너 버전·OS·동시 한도·지금 돌리는 run id. 전부 선택이고 서버가
 * 폭을 자른다(버전 40자·OS 80자, 동시 한도 1~8).
 */
public record RunnerHeartbeatRequest(String version, String os, Integer maxConcurrency, List<Long> runIds) {
}
