package com.platform.agentservice.review;

import com.platform.agentservice.authz.AgentCaller;
import com.platform.agentservice.review.dto.ReviewSettingRequest;
import com.platform.agentservice.review.dto.ReviewSettingResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 리뷰어 지정(AI 팀 설정, D-P4b-1). 전역 층은 전역 관리자만(조회 포함), 프로젝트 층 변경은 그 프로젝트 관리자(§7). 프로젝트 조회는
 * 인증 사용자 누구나 — 실행 위치 조회와 같은 기준(비밀 없음, "리뷰어 없음 — done 불가" 안내용).
 */
@RestController
@RequestMapping("/api/agent/review-settings")
@RequiredArgsConstructor
public class ReviewSettingController {

    private final ReviewerResolver resolver;

    @GetMapping("/platform")
    @PreAuthorize("@agentAuthz.canManageGlobal(authentication)")
    public ReviewSettingResponse platform() {
        return resolver.platform();
    }

    @PutMapping("/platform")
    @PreAuthorize("@agentAuthz.canManageGlobal(authentication)")
    public ReviewSettingResponse putPlatform(@RequestBody ReviewSettingRequest request, Authentication authentication) {
        return resolver.putPlatform(request == null ? null : request.personaId(), AgentCaller.from(authentication).userId());
    }

    @DeleteMapping("/platform")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@agentAuthz.canManageGlobal(authentication)")
    public void deletePlatform() {
        resolver.deletePlatform();
    }

    @GetMapping("/projects/{projectId}")
    public ReviewSettingResponse project(@PathVariable long projectId) {
        return resolver.project(projectId);
    }

    @PutMapping("/projects/{projectId}")
    @PreAuthorize("@agentAuthz.canManageProject(authentication, #projectId)")
    public ReviewSettingResponse putProject(@PathVariable long projectId, @RequestBody ReviewSettingRequest request,
                                            Authentication authentication) {
        return resolver.putProject(projectId, request == null ? null : request.personaId(),
                AgentCaller.from(authentication).userId());
    }

    @DeleteMapping("/projects/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@agentAuthz.canManageProject(authentication, #projectId)")
    public void deleteProject(@PathVariable long projectId) {
        resolver.deleteProject(projectId);
    }
}
