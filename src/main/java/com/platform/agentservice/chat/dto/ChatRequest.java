package com.platform.agentservice.chat.dto;

/**
 * {@code POST /api/agent/personas/{id}/chat} 요청(P3g ②). 검증은 서비스가 고정 문구로 한다 — 빈 검증 애너테이션의 거부 응답에
 * 입력값(대화 본문)이 실리지 않게.
 *
 * @param message   1~500자
 * @param sessionId 첫 턴은 생략 — 서버가 발급해 응답에 싣는다
 * @param projectId 선택 — LLM 키 해석(프로젝트 층)·예산(프로젝트 캡) 문맥
 */
public record ChatRequest(String message, String sessionId, Long projectId) {
}
