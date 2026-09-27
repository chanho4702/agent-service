package com.platform.agentservice.credential;

import java.util.Locale;

public enum LlmProvider {
    ANTHROPIC;

    /** 모르는 값은 400 — 저장해도 쓸 곳이 없는 키를 받지 않는다. */
    public static LlmProvider parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("provider가 필요합니다(ANTHROPIC)");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("지원하지 않는 provider입니다(ANTHROPIC만 가능)");
        }
    }
}
