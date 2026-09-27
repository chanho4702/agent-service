package com.platform.agentservice.credential;

import com.platform.agentservice.authz.AgentCaller;
import com.platform.agentservice.credential.dto.CredentialPutRequest;
import com.platform.agentservice.credential.dto.CredentialStatusResponse;
import com.platform.agentservice.credential.dto.ProjectCredentialResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * LLM 키 2층(D-P3h-2, AGP-66). 전역 층은 전역 관리자만, 프로젝트 층은 그 프로젝트 관리자(§7 — 전역 관리자 또는 PROJECT ADMIN).
 * 조회도 관리자 전용이다 — 힌트 4자·갱신자도 운영 정보라 일반 사용자에게 닫는다(PAT 목록과 같은 기준).
 */
@RestController
@RequestMapping("/api/agent/credentials")
@RequiredArgsConstructor
public class CredentialController {

    private final CredentialService credentialService;

    @GetMapping("/platform")
    @PreAuthorize("@agentAuthz.canManageGlobal(authentication)")
    public CredentialStatusResponse platform() {
        return credentialService.platform();
    }

    @PutMapping("/platform")
    @PreAuthorize("@agentAuthz.canManageGlobal(authentication)")
    public CredentialStatusResponse putPlatform(@RequestBody CredentialPutRequest request, Authentication authentication) {
        return credentialService.putPlatform(request, AgentCaller.from(authentication));
    }

    @DeleteMapping("/platform")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@agentAuthz.canManageGlobal(authentication)")
    public void deletePlatform(Authentication authentication) {
        credentialService.deletePlatform(AgentCaller.from(authentication));
    }

    @GetMapping("/projects/{projectId}")
    @PreAuthorize("@agentAuthz.canManageProject(authentication, #projectId)")
    public ProjectCredentialResponse project(@PathVariable long projectId) {
        return credentialService.project(projectId);
    }

    @PutMapping("/projects/{projectId}")
    @PreAuthorize("@agentAuthz.canManageProject(authentication, #projectId)")
    public CredentialStatusResponse putProject(@PathVariable long projectId, @RequestBody CredentialPutRequest request,
                                               Authentication authentication) {
        return credentialService.putProject(projectId, request, AgentCaller.from(authentication));
    }

    @DeleteMapping("/projects/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@agentAuthz.canManageProject(authentication, #projectId)")
    public void deleteProject(@PathVariable long projectId, Authentication authentication) {
        credentialService.deleteProject(projectId, AgentCaller.from(authentication));
    }
}
