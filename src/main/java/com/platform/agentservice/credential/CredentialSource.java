package com.platform.agentservice.credential;

/** 해석 결과의 출처(D-P3h-3) — 우선순위 순서 그대로다. 원장·로그에는 이 값만 남긴다. */
public enum CredentialSource {
    PROJECT,
    PLATFORM,
    /** 서비스 프로세스 env {@code ANTHROPIC_API_KEY}(P3h 이전 구성 호환). */
    ENV,
    NONE
}
