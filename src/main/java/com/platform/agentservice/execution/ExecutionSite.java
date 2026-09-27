package com.platform.agentservice.execution;

import java.util.Locale;

/**
 * run이 실제로 실행되는 곳(P4a D-P4-1). {@code SERVER}는 플랫폼 쪽 — 인프로세스 워커(로컬 dev) 또는 PLATFORM 러너 컨테이너
 * (D-P4-5), {@code LOCAL}은 사용자 PC의 로컬 러너(그 PC의 Claude 구독·자기 키로 과금).
 */
public enum ExecutionSite {
    SERVER,
    LOCAL;

    /** 요청 문자열 해석 — null·공백은 "지정 안 함"(null). 모르는 값은 400({@code IllegalArgumentException} → common-starter). */
    public static ExecutionSite parseOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ExecutionSite.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("executionSite는 SERVER 또는 LOCAL이어야 합니다");
        }
    }
}
