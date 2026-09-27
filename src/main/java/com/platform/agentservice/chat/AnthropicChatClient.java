package com.platform.agentservice.chat;

import java.util.List;

/** Anthropic Messages API 한 번 호출(P3g 수다) — 도구 없이 텍스트만. 키는 요청마다 해석된 값이 온다(프로젝트/전역/env). */
public interface AnthropicChatClient {

    /**
     * @throws com.platform.common.error.ServiceUnavailableException 키 거부·과부하·연결 실패 등 답을 못 받은 모든 경우(고정 문구)
     */
    Completion complete(String apiKey, String model, int maxTokens, String system, List<Message> messages);

    /** @param role {@code user} | {@code assistant} */
    record Message(String role, String content) {
    }

    record Completion(String text, long inputTokens, long outputTokens) {
    }
}
