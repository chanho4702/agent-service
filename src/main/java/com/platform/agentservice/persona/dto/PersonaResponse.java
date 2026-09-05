package com.platform.agentservice.persona.dto;

import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRole;

/** {@code POST/GET /api/agent/personas} 응답 항목. */
public record PersonaResponse(Long id, Long memberId, String slug, PersonaRole role, String name, String emoji, boolean active) {
    public static PersonaResponse from(Persona p) {
        return new PersonaResponse(p.getId(), p.getMemberId(), p.getSlug(), p.getRole(), p.getName(), p.getEmoji(), p.isActive());
    }
}
