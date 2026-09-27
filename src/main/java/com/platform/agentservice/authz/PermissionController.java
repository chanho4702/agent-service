package com.platform.agentservice.authz;

import com.platform.common.error.ServiceUnavailableException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 버튼 노출 판정용 권한 조회(D-P3f-6) — 인증된 사용자 누구나. 화면 숨김은 편의일 뿐이고 실제 판정은 각 관리 API가
 * 다시 한다(org 장애면 거기서 503). 이 API는 org 장애·판정 실패에도 canManage=false로 답한다 — 버튼 노출 힌트가 503으로
 * 깨지면 사무실 화면 전체가 오류가 된다.
 */
@RestController
@RequestMapping("/api/agent/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final AgentAuthz agentAuthz;

    public record PermissionResponse(boolean canManage, boolean isGlobalAdmin) {}

    /** projectId가 없으면 전사 공용 관리 여부 — 전역 관리자만 true. */
    @GetMapping
    public PermissionResponse permissions(@RequestParam(required = false) Long projectId, Authentication authentication) {
        AgentCaller caller = AgentCaller.from(authentication);
        boolean canManage;
        try {
            canManage = projectId == null ? caller.globalAdmin() : agentAuthz.canManage(caller, projectId);
        } catch (ServiceUnavailableException e) {
            canManage = false;
        }
        return new PermissionResponse(canManage, caller.globalAdmin());
    }
}
