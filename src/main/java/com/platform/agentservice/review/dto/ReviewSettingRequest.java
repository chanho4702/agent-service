package com.platform.agentservice.review.dto;

/** {@code PUT /api/agent/review-settings/platform|projects/{projectId}} — 리뷰어 페르소나 id. 해제는 DELETE. */
public record ReviewSettingRequest(Long personaId) {
}
