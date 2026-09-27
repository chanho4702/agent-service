package com.platform.agentservice.credential;

/**
 * 저장 전 키 검증(PUT {@code validate=true}). 거부된 키는 {@link IllegalArgumentException}(400 "키 검증 실패"),
 * 검증 대상에 닿지 못하면 {@code ServiceUnavailableException}(503) — "키가 틀렸다"와 "지금 확인할 수 없다"를 섞지 않는다.
 * 어느 쪽 메시지에도 키 원문은 없다.
 */
public interface AnthropicKeyValidator {

    String INVALID_MESSAGE = "키 검증 실패 — Anthropic이 이 키를 거부했습니다(키 값·권한·조직 상태를 확인하세요)";
    String UNREACHABLE_MESSAGE = "키 검증 실패 — Anthropic API에 연결할 수 없어 확인하지 못했습니다(네트워크·프록시 확인 후 다시 시도하거나 검증 없이 저장하세요)";

    void validate(String apiKey);
}
