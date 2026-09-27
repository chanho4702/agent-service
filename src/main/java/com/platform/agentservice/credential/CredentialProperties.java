package com.platform.agentservice.credential;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * LLM 키 저장 설정(P3h) — {@code platform.agent.credentials.*}.
 *
 * @param masterKey       AES-256 마스터 키(base64 32바이트, env {@code AGENT_CREDENTIAL_MASTER_KEY}). 비었거나 형식이 틀리면 저장 API가 503.
 * @param anthropicApiUrl 키 검증(validate=true) 호출 대상 — 사내 프록시 경유 설치를 위해 바꿀 수 있게 둔다.
 */
@ConfigurationProperties(prefix = "platform.agent.credentials")
public record CredentialProperties(String masterKey, String anthropicApiUrl) {

    @ConstructorBinding
    public CredentialProperties(String masterKey, @DefaultValue("https://api.anthropic.com") String anthropicApiUrl) {
        this.masterKey = masterKey;
        this.anthropicApiUrl = anthropicApiUrl;
    }

    /** 레코드 기본 toString은 마스터 키를 그대로 찍는다 — 바인딩 오류·디버그 로그로 새지 않게 가린다. */
    @Override
    public String toString() {
        return "CredentialProperties{masterKey=" + (masterKey == null || masterKey.isBlank() ? "(unset)" : "(set)")
                + ", anthropicApiUrl=" + anthropicApiUrl + "}";
    }
}
