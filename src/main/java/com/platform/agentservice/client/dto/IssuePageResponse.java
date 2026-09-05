package com.platform.agentservice.client.dto;

import java.util.List;

/** {@code GET /api/alm/issues/search} 응답 — alm-backend {@code IssuePageResponse}와 동일 shape(S10). */
public record IssuePageResponse(List<IssueResponse> items, int page, int size, long total) {
}
