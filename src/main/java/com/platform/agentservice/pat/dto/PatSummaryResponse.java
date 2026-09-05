package com.platform.agentservice.pat.dto;

import java.time.Instant;

/** {@code GET /api/agent/tokens} 목록 항목 — 해시는 절대 담지 않는다. */
public record PatSummaryResponse(
        Long id,
        String label,
        String personaSlug,
        Instant createdAt,
        Instant expiresAt,
        Instant lastUsedAt,
        boolean revoked) {
}
