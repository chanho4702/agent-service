package com.platform.agentservice.persona.dto;

import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRole;

/**
 * {@code POST/GET/PATCH /api/agent/personas} 응답 항목. {@code projectId}가 null이면 전사 공용 페르소나(P3f).
 * {@code defaultModel}·{@code skills}·{@code avatarConfig}(AGP-62)는 미설정이면 null — {@code avatarConfig}는 저장된 JSON 문자열 그대로다.
 */
public record PersonaResponse(Long id, Long memberId, String slug, PersonaRole role, String name, String emoji, boolean active,
                              Long projectId, String defaultModel, String skills, String avatarConfig) {

    /** 편집 필드가 없는 응답(기존 호출부·테스트). */
    public PersonaResponse(Long id, Long memberId, String slug, PersonaRole role, String name, String emoji, boolean active,
                           Long projectId) {
        this(id, memberId, slug, role, name, emoji, active, projectId, null, null, null);
    }

    public static PersonaResponse from(Persona p) {
        return new PersonaResponse(p.getId(), p.getMemberId(), p.getSlug(), p.getRole(), p.getName(), p.getEmoji(), p.isActive(),
                p.getProjectId(), p.getDefaultModel(), p.getSkills(), p.getAvatarConfig());
    }
}
