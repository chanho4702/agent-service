package com.platform.agentservice.pat.dto;

import java.time.Instant;

/**
 * {@code GET /api/agent/tokens} 목록 항목 — 해시는 절대 담지 않는다.
 *
 * <p>D-P4-3b: {@code kind}는 HUMAN(사람용 PAT)|RUN(워커 run 토큰 — run 종료 시 철회), {@code noExpiry}는 무기한 사람용 토큰(화면 "무기한"
 * 경고), {@code expiringSoon}은 7일 안에 만료(경고 배지).
 */
public record PatSummaryResponse(
        Long id,
        String label,
        String personaSlug,
        Instant createdAt,
        Instant expiresAt,
        Instant lastUsedAt,
        boolean revoked,
        String kind,
        boolean noExpiry,
        boolean expiringSoon) {

    public static final String KIND_HUMAN = "HUMAN";
    public static final String KIND_RUN = "RUN";

    public PatSummaryResponse(Long id, String label, String personaSlug, Instant createdAt, Instant expiresAt,
                              Instant lastUsedAt, boolean revoked) {
        this(id, label, personaSlug, createdAt, expiresAt, lastUsedAt, revoked, KIND_HUMAN, expiresAt == null, false);
    }
}
