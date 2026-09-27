package com.platform.agentservice.run.dto;

import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRole;

import java.util.List;

/** {@code POST /api/agent/meetings} 201 — 저장된 회의 run 요약과 해석된 참석자(첫 번째가 진행자). */
public record MeetingCreatedResponse(RunSummaryResponse run, List<MeetingAttendee> attendees) {

    public record MeetingAttendee(long personaId, String slug, String name, PersonaRole role, String emoji) {
        public static MeetingAttendee of(Persona p) {
            return new MeetingAttendee(p.getId(), p.getSlug(), p.getName(), p.getRole(), p.getEmoji());
        }
    }
}
