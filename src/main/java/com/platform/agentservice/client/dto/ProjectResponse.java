package com.platform.agentservice.client.dto;

/**
 * alm-backend {@code ProjectResponse}에서 컨텍스트 도구가 쓰는 요약 필드만 옮겨왔다(S10) —
 * {@code description/category/leadId/defaultAssignee/icon/color/url/version/createdAt/
 * updatedAt/archivedAt/deletedAt/purgeAt}은 {@code list_projects}/{@code get_project_context}
 * 요약에 필요하지 않다.
 */
public record ProjectResponse(
        long id,
        String key,
        String name
) {
}
