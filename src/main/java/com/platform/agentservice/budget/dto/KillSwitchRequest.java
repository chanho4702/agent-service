package com.platform.agentservice.budget.dto;

/** {@code POST /api/agent/kill-switch} 요청 본문. */
public record KillSwitchRequest(boolean on) {
}
