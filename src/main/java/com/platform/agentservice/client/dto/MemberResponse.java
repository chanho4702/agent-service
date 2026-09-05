package com.platform.agentservice.client.dto;

/**
 * org-service {@code MemberResponse}(S10)에서 {@code get_project_context} 명단 요약이 쓰는
 * 필드만 옮겨왔다 — {@code email/status/avatarUrl/avatarUpdatedAt}은 담당자 후보를 보여주는
 * 용도에 필요하지 않다.
 */
public record MemberResponse(
        Long id,
        String displayName,
        String kind
) {
}
