package com.platform.agentservice.run.dto;

import com.platform.agentservice.run.RunType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * {@code POST /api/agent/meetings} 요청(P3b, D-P3b-4①). {@code type}은 MEETING·RETRO·ESCALATION·MANAGER(P3c 매니저 순찰)만 받는다(그 밖은 서비스가 400).
 *
 * <p>{@code agendaIssueKey} 상한은 {@code run.issue_key VARCHAR(40)}과 같다({@code RequestDtoColumnWidthTest}).
 * {@code agenda}는 run.instruction(TEXT)에 저장돼 워커 프롬프트에 통째로 실리므로 USER run 지시문과 같은 애플리케이션 상한을 둔다.
 * {@code personaSlugs} 상한 20은 {@code run.attendee_persona_ids VARCHAR(400)}에 id 목록이 넉넉히 들어가는 범위다.
 */
public record MeetingCreateRequest(
        @NotNull(message = "type은 필수입니다") RunType type,
        @NotNull(message = "projectId는 필수입니다") Long projectId,
        @Size(max = 40, message = "agendaIssueKey는 40자 이하여야 합니다") String agendaIssueKey,
        @Size(max = 4000, message = "agenda는 4000자 이하여야 합니다") String agenda,
        @Size(max = 20, message = "personaSlugs는 20명 이하여야 합니다") List<String> personaSlugs,
        String executionSite) {

    /** 실행 위치 지정 없는 요청(기존 호출부). */
    public MeetingCreateRequest(RunType type, Long projectId, String agendaIssueKey, String agenda, List<String> personaSlugs) {
        this(type, projectId, agendaIssueKey, agenda, personaSlugs, null);
    }
}
