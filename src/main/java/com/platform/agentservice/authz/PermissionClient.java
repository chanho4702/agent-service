package com.platform.agentservice.authz;

import com.platform.proto.org.v1.ResourceType;

/**
 * org-service 권한 판정 — 프로젝트·스페이스 ADMIN 여부의 진실 소스는 org다. 프로젝트 생성자는 alm이 생성 시
 * PROJECT ADMIN grant를 자동 부여하므로 이 판정 하나로 "생성자는 무조건"과 위임이 함께 충족된다(D-P3f-1).
 * 구현은 판정 실패 시 예외 대신 {@link PermissionDecision#orgFailure()}를 돌려준다(fail-closed).
 */
public interface PermissionClient {

    /** {@code CheckPermission(user, type, resourceId, ADMIN)}. GLOBAL이면 resourceId는 빈 문자열. */
    PermissionDecision checkAdmin(long userId, ResourceType type, String resourceId);
}
