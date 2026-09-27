package com.platform.agentservice.credential.dto;

/**
 * {@code GET /api/agent/credentials/projects/{id}} — 프로젝트 층 상태 + 실제 적용될 키의 출처({@code effective}).
 * effective는 복호화 가능한 키만 센다 — 저장돼 있어도 깨진 행이면 아래 층이 보인다.
 */
public record ProjectCredentialResponse(CredentialStatusResponse project, Effective effective) {

    /** @param scope PROJECT|PLATFORM|ENV|NONE */
    public record Effective(String scope, String keyHint) {}
}
