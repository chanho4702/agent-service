package com.platform.agentservice.execution.dto;

import com.platform.agentservice.execution.ExecutionSite;

/**
 * {@code GET/PUT /api/agent/execution-site/projects/{projectId}}. {@code site}는 프로젝트에 저장된 값(없으면 null — 전역 기본을
 * 따른다), {@code effectiveSite}는 요청 지정이 없을 때 새 run이 실제로 받을 위치. {@code inProcess}는 SERVER run이 서비스
 * 프로세스 안에서 도는지(false면 PLATFORM 러너가 있어야 SERVER run이 돈다 — 화면 안내용).
 */
public record ProjectExecutionSiteResponse(long projectId, ExecutionSite site, ExecutionSite effectiveSite,
                                           ExecutionSite defaultSite, boolean inProcess) {
}
