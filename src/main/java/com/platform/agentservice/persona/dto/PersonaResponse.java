package com.platform.agentservice.persona.dto;

import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRole;

/**
 * {@code POST/GET /api/agent/personas} 응답 항목 — 인증 사용자 누구나 보는 표면이다. {@code projectId}가 null이면 전사 공용
 * 페르소나(P3f). {@code avatarConfig}(AGP-62)는 카드·사무실 렌더용 시각 정보라 싣고(저장된 JSON 문자열, 미설정 null),
 * 말투·기본 모델·스킬은 싣지 않는다 — 그건 관리자 전용 상세({@link PersonaDetailResponse})에만 있다.
 */
public record PersonaResponse(Long id, Long memberId, String slug, PersonaRole role, String name, String emoji, boolean active,
                              Long projectId, String avatarConfig) {

    /** 아바타가 없는 응답(기존 호출부·테스트). */
    public PersonaResponse(Long id, Long memberId, String slug, PersonaRole role, String name, String emoji, boolean active,
                           Long projectId) {
        this(id, memberId, slug, role, name, emoji, active, projectId, null);
    }

    public static PersonaResponse from(Persona p) {
        return new PersonaResponse(p.getId(), p.getMemberId(), p.getSlug(), p.getRole(), p.getName(), p.getEmoji(), p.isActive(),
                p.getProjectId(), p.getAvatarConfig());
    }
}
