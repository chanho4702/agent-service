package com.platform.agentservice.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * 사무실 1:1 대화 설정(P3g, AGP-65) — {@code platform.agent.chat.*}.
 *
 * @param enabled          설치 옵션 {@code CHAT_ENABLED}. false면 수다 API 503, office {@code features.chat=false}.
 * @param model            수다 모델 {@code CHAT_MODEL} — 짧은 잡담이라 비용 우선.
 * @param maxTokens        응답 상한 토큰. 답변 600자 + 메타 한 줄이 들어갈 만큼만.
 * @param rateLimit        사용자당 창 안 허용 횟수 {@code CHAT_RATE_LIMIT}.
 * @param rateWindow       슬라이딩 창 {@code CHAT_RATE_WINDOW}.
 * @param inputUsdPerMtok  원장 적재용 입력 단가(USD/백만 토큰) — 모델을 바꾸면 같이 바꾼다.
 * @param outputUsdPerMtok 출력 단가.
 * @param contextTurns     LLM에 싣는 최근 턴 수(1턴 = 사용자 한 마디 + 페르소나 한 마디).
 * @param retentionDays    대화 기록 보존 일수.
 * @param retentionMax     사용자×페르소나당 보존 건수.
 */
@ConfigurationProperties(prefix = "platform.agent.chat")
public record ChatProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("claude-haiku-4-5-20251001") String model,
        @DefaultValue("400") int maxTokens,
        @DefaultValue("30") int rateLimit,
        @DefaultValue("10m") Duration rateWindow,
        @DefaultValue("1") BigDecimal inputUsdPerMtok,
        @DefaultValue("5") BigDecimal outputUsdPerMtok,
        @DefaultValue("10") int contextTurns,
        @DefaultValue("90") int retentionDays,
        @DefaultValue("500") int retentionMax
) {
}
