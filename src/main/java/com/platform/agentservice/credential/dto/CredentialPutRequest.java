package com.platform.agentservice.credential.dto;

/**
 * {@code PUT /api/agent/credentials/**} 본문. 빈 검증 애너테이션을 달지 않는 이유: 검증 실패 응답·로그에 거부값(원문)이 실릴 수 있어
 * 서비스가 원문을 싣지 않는 문구로 직접 검사한다. 레코드 기본 toString도 원문을 찍으므로 가린다.
 */
public record CredentialPutRequest(String provider, String apiKey, Boolean validate) {

    public boolean validateRequested() {
        return Boolean.TRUE.equals(validate);
    }

    @Override
    public String toString() {
        return "CredentialPutRequest{provider=" + provider + ", apiKey=(redacted), validate=" + validate + "}";
    }
}
