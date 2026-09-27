package com.platform.agentservice.execution.dto;

/** {@code PUT /api/agent/execution-site/projects/{projectId}} — {@code site}: SERVER|LOCAL, null·빈 값이면 설정 해제(전역 기본). */
public record ProjectExecutionSiteRequest(String site) {
}
