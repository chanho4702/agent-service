package com.platform.agentservice.directive;

import com.platform.agentservice.authz.AgentAuthz;
import com.platform.agentservice.authz.AgentCaller;
import com.platform.agentservice.directive.dto.RunDirectiveCreateRequest;
import com.platform.agentservice.directive.dto.RunDirectiveResponse;
import com.platform.agentservice.run.RunRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 실행 중 지시(AGP-67). 지시는 cancel/resume과 같은 관리 행위(전역 관리자 또는 run 프로젝트 관리자 — §7). 목록은 run 목록처럼 인증
 * 사용자 누구나 보되 본문은 관리자에게만 — 판정이 org 장애·실패로 안 되면 503 대신 본문을 가린다(권한 조회 API와 같은 방향: 조회
 * 화면이 깨지지 않게, 실제 관리 행위는 POST가 다시 판정한다).
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/runs/{id}/directives")
@RequiredArgsConstructor
public class RunDirectiveController {

    private final RunDirectiveService directiveService;
    private final RunRepository runRepository;
    private final AgentAuthz agentAuthz;

    @PostMapping
    @PreAuthorize("@agentAuthz.canManageRun(authentication, #id)")
    @ResponseStatus(HttpStatus.CREATED)
    public RunDirectiveResponse create(@PathVariable long id, @Valid @RequestBody RunDirectiveCreateRequest request,
                                       Authentication authentication) {
        return directiveService.create(id, request.text(), AgentCaller.from(authentication).userId());
    }

    @GetMapping
    public List<RunDirectiveResponse> list(@PathVariable long id, Authentication authentication) {
        return directiveService.list(id, canSeeText(id, authentication));
    }

    private boolean canSeeText(long runId, Authentication authentication) {
        try {
            AgentCaller caller = AgentCaller.from(authentication);
            if (caller.globalAdmin()) {
                return true;
            }
            return runRepository.findById(runId).map(run -> agentAuthz.canManage(caller, run.getProjectId())).orElse(false);
        } catch (RuntimeException e) {
            log.debug("지시 목록 본문 권한 판정 실패 — 본문을 가립니다: run={} {}", runId, e.getMessage());
            return false;
        }
    }
}
