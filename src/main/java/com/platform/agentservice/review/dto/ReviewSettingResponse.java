package com.platform.agentservice.review.dto;

import com.platform.agentservice.review.ReviewerSource;

/**
 * {@code GET/PUT /api/agent/review-settings/platform|projects/{projectId}}. {@code setting}은 그 범위에 저장된 지정(없으면 null —
 * 가리키는 페르소나가 없어졌으면 slug·name이 null), {@code effective}는 지금 TASK가 DONE이면 실제로 검증을 맡을 리뷰어와 출처다.
 * {@code effective.source=NONE}이면 personaId·slug·name이 전부 null이고 검증 run을 띄울 수 없다(done 불가 경고).
 */
public record ReviewSettingResponse(Setting setting, Effective effective) {

    public record Setting(long personaId, String slug, String name) {
    }

    public record Effective(Long personaId, String slug, String name, ReviewerSource source) {
    }
}
