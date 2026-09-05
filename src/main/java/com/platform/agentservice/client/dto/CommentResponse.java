package com.platform.agentservice.client.dto;

import java.time.Instant;

/** alm-backend {@code CollaborationService.CommentResponse}와 동일 shape(S10). */
public record CommentResponse(long id, long issueId, long authorId, String body, Instant createdAt, Instant updatedAt) {
}
