package com.platform.agentservice.persona.dto;

import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRole;

/**
 * {@code GET/PATCH /api/agent/personas/{id}} 응답(AGP-62) — 관리자 전용 상세. 목록 필드({@link PersonaResponse}) + 편집 다이얼로그
 * 프리필에 필요한 {@code voicePrompt}·{@code defaultModel}·{@code skills}. 미설정 필드는 null.
 */
public record PersonaDetailResponse(Long id, Long memberId, String slug, PersonaRole role, String name, String emoji,
                                    boolean active, Long projectId, String avatarConfig, String voicePrompt,
                                    String defaultModel, String skills) {

    public static PersonaDetailResponse from(Persona p) {
        return new PersonaDetailResponse(p.getId(), p.getMemberId(), p.getSlug(), p.getRole(), p.getName(), p.getEmoji(),
                p.isActive(), p.getProjectId(), p.getAvatarConfig(), p.getVoicePrompt(), p.getDefaultModel(), p.getSkills());
    }
}
