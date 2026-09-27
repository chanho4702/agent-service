package com.platform.agentservice.execution;

import com.platform.agentservice.authz.AgentCaller;
import com.platform.agentservice.execution.dto.ProjectExecutionSiteRequest;
import com.platform.agentservice.execution.dto.ProjectExecutionSiteResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 프로젝트 실행 위치(AI 팀 설정, D-P4-4). 조회는 인증 사용자 누구나(사무실·설정 화면 안내용 — 비밀 없음), 변경은 그 프로젝트
 * 관리자(§7 — 전역 관리자 또는 PROJECT ADMIN).
 */
@RestController
@RequestMapping("/api/agent/execution-site")
@RequiredArgsConstructor
public class ExecutionSiteController {

    private final ExecutionSiteResolver resolver;

    @GetMapping("/projects/{projectId}")
    public ProjectExecutionSiteResponse project(@PathVariable long projectId) {
        return resolver.project(projectId);
    }

    @PutMapping("/projects/{projectId}")
    @PreAuthorize("@agentAuthz.canManageProject(authentication, #projectId)")
    public ProjectExecutionSiteResponse setProject(@PathVariable long projectId,
                                                   @RequestBody ProjectExecutionSiteRequest request,
                                                   Authentication authentication) {
        return resolver.setProject(projectId, request == null ? null : request.site(),
                AgentCaller.from(authentication).userId());
    }
}
