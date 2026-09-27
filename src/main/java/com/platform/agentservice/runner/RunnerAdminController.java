package com.platform.agentservice.runner;

import com.platform.agentservice.authz.AgentCaller;
import com.platform.agentservice.runner.dto.RunnerCreateRequest;
import com.platform.agentservice.runner.dto.RunnerCreatedResponse;
import com.platform.agentservice.runner.dto.RunnerSummaryResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 러너 관리(AI 팀 설정, D-P4-4) — JWT 체인. 권한은 페르소나 관리와 같다(§7): 프로젝트 러너는 그 프로젝트 관리자, 플랫폼 전역 러너는
 * 전역 관리자. PLATFORM 러너는 여기서 발급·철회하지 않는다(env {@code AGENT_PLATFORM_RUNNER_TOKEN}이 정본).
 */
@RestController
@RequestMapping(RunnerPaths.BASE)
@RequiredArgsConstructor
public class RunnerAdminController {

    private final RunnerService runnerService;

    /** LOCAL 러너 토큰 발급 — 응답의 {@code token}(agr_)은 이때 한 번만 보인다. */
    @PostMapping
    @PreAuthorize("@agentAuthz.canManageProject(authentication, #request.projectId())")
    @ResponseStatus(HttpStatus.CREATED)
    public RunnerCreatedResponse create(@Valid @RequestBody RunnerCreateRequest request, Authentication authentication) {
        return runnerService.create(request, AgentCaller.from(authentication));
    }

    /** projectId 없으면 전체(전역 관리자만), 있으면 그 프로젝트 러너 + 플랫폼 전역 LOCAL 러너. */
    @GetMapping
    @PreAuthorize("@agentAuthz.canManageProject(authentication, #projectId)")
    public List<RunnerSummaryResponse> list(@RequestParam(required = false) Long projectId) {
        return runnerService.list(projectId);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@runnerService.canManageRunner(authentication, #id)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable long id, Authentication authentication) {
        runnerService.revoke(id, AgentCaller.from(authentication));
    }
}
