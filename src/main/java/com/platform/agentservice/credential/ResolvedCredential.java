package com.platform.agentservice.credential;

/**
 * 해석 결과(D-P3h-3). {@code apiKey}는 메모리에서만 쓰고 저장·로그·응답에 싣지 않는다 — 밖으로 내보낼 것은 {@link #source()}와
 * {@link #keyHint()}뿐이다. 레코드 기본 toString은 원문을 찍으므로 가린다(로그 한 줄로 키가 새는 경로를 구조로 막는다).
 */
public record ResolvedCredential(String apiKey, CredentialSource source) {

    public static final ResolvedCredential NONE = new ResolvedCredential(null, CredentialSource.NONE);

    public boolean present() {
        return apiKey != null && !apiKey.isEmpty();
    }

    /** DB 층(PROJECT·PLATFORM) 키인가 — 워커에 run별로 주입할 대상. ENV는 기존 passthrough 경로가 그대로 싣는다. */
    public boolean stored() {
        return source == CredentialSource.PROJECT || source == CredentialSource.PLATFORM;
    }

    public String keyHint() {
        return present() ? CredentialService.hintOf(apiKey) : null;
    }

    @Override
    public String toString() {
        return "ResolvedCredential{source=" + source + ", keyHint=" + keyHint() + "}";
    }
}
