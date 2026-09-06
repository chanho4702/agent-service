package com.platform.agentservice.client.dto;

import java.time.Instant;

/** alm-backend {@code CollaborationService.WebLinkResponse}와 동일 shape(P2a T6). */
public record WebLinkResponse(long id, long issueId, String url, String title, String kind, long createdBy, Instant createdAt) {
}
