package com.platform.agentservice.run;

import com.platform.agentservice.run.dto.MeetingCreateRequest;
import com.platform.agentservice.run.dto.MeetingCreatedResponse;
import com.platform.agentservice.run.dto.RunSummaryResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회의 소집(P3b, D-P3b-4①) — 예산을 쓰는 행위라 USER run 생성과 같이 관리자만(P3f: 전역 관리자 또는 요청 프로젝트의 관리자). 실행 제출을 여기서 하는 이유는
 * {@link RunController#create}와 같다({@code @Async} 자기호출 회피). 제출이 거부돼도 run은 QUEUED로 커밋됐으므로 201.
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/meetings")
@RequiredArgsConstructor
public class MeetingController {

    private final MeetingService meetingService;
    private final RunService runService;

    @PostMapping
    @PreAuthorize("@agentAuthz.canManageProject(authentication, #request.projectId())")
    @ResponseStatus(HttpStatus.CREATED)
    public MeetingCreatedResponse create(@Valid @RequestBody MeetingCreateRequest request) {
        MeetingService.MeetingCreated created = meetingService.createMeeting(request.type(), request.projectId(),
                request.agendaIssueKey(), request.agenda(), request.personaSlugs(), request.executionSite());
        Run run = created.run();
        try {
            runService.execute(run.getId());
        } catch (TaskRejectedException e) {
            log.warn("회의 run 실행 제출이 거부돼 QUEUED로 남깁니다 — 다음 드레인 틱이 재시도합니다: id={} error={}",
                    run.getId(), e.getMessage());
        }
        return new MeetingCreatedResponse(RunSummaryResponse.of(run),
                created.attendees().stream().map(MeetingCreatedResponse.MeetingAttendee::of).toList());
    }
}
