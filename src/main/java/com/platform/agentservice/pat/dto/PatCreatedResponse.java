package com.platform.agentservice.pat.dto;

import java.time.Instant;

/**
 * 발급 응답 — 평문 토큰은 이 응답에만 담긴다. 이후로는 해시만 남아 재조회가 불가능하니
 * 호출자가 이 시점에 반드시 안전한 곳에 저장해야 한다. {@code expiresAt}이 null이면 무기한(D-P4-3b).
 */
public record PatCreatedResponse(String token, Long id, String label, String personaSlug, Instant expiresAt) {

    public PatCreatedResponse(String token, Long id, String label, String personaSlug) {
        this(token, id, label, personaSlug, null);
    }
}
