package com.platform.agentservice.credential.dto;

import com.platform.agentservice.credential.Credential;

import java.time.Instant;

/** 한 층의 키 설정 상태 — 프론트 계약(D-P3h-2). {@code keyHint}는 원문 끝 4자(앞의 "…"는 프론트가 붙인다). */
public record CredentialStatusResponse(boolean set, String provider, String keyHint, Long updatedBy, Instant updatedAt) {

    public static final CredentialStatusResponse UNSET = new CredentialStatusResponse(false, null, null, null, null);

    public static CredentialStatusResponse of(Credential c) {
        return new CredentialStatusResponse(true, c.getProvider().name(), c.getKeyHint(), c.getUpdatedBy(), c.getUpdatedAt());
    }
}
