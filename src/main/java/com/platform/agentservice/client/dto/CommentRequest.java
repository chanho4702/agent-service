package com.platform.agentservice.client.dto;

import java.util.List;

/** {@code POST /api/alm/issues/{issueId}/comments} 요청 body — alm-backend {@code CommentRequest}와 동일 shape. */
public record CommentRequest(String body, List<Long> mentionedUserIds) {
}
