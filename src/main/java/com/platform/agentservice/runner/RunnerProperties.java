package com.platform.agentservice.runner;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 러너 설정(P4a AGP-69) — {@code platform.agent.runner.*}.
 *
 * <ul>
 *   <li>{@code publicMcpUrl}: claim 명세에 싣는 MCP 주소 — 러너는 nginx·게이트웨이를 거쳐 들어오므로 {@code worker.mcp-url}
 *       (서비스 자신이 보는 주소)과 다르다. 비면 {@code worker.mcp-url}로 대신한다.</li>
 *   <li>{@code offlineAfter}: heartbeat가 이만큼 끊기면 오프라인 — 그 러너의 RUNNING run은 사고형 BLOCKED("러너 연결 끊김").</li>
 *   <li>{@code livenessIntervalMs}: 생존 판정 주기.</li>
 *   <li>{@code platformToken}: PLATFORM 러너 토큰({@code agr_}, env {@code AGENT_PLATFORM_RUNNER_TOKEN}) — 기동 시 해시로 러너 행을
 *       맞춘다. 비면 플랫폼 러너가 없다.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "platform.agent.runner")
public record RunnerProperties(String publicMcpUrl, Duration offlineAfter, Long livenessIntervalMs, String platformToken) {

    public RunnerProperties {
        offlineAfter = offlineAfter == null || offlineAfter.isNegative() || offlineAfter.isZero()
                ? Duration.ofSeconds(90) : offlineAfter;
        livenessIntervalMs = livenessIntervalMs == null || livenessIntervalMs <= 0 ? 30_000L : livenessIntervalMs;
    }

    /** 원문 토큰을 로그로 흘리지 않는다. */
    @Override
    public String toString() {
        return "RunnerProperties{publicMcpUrl=" + publicMcpUrl + ", offlineAfter=" + offlineAfter
                + ", platformToken=" + (platformToken == null || platformToken.isBlank() ? "unset" : "set") + "}";
    }
}
