package com.platform.agentservice.audit;

/**
 * 감사 행의 실행 출처(AGP-63). V12 이전 행은 NULL(미상).
 *
 * <ul>
 *   <li>{@link #WORKER} — 우리 워커 run이 run 토큰으로 부른 MCP 도구(예산·리뷰·게이트·킬 스위치 적용)</li>
 *   <li>{@link #EXTERNAL} — 사람이 발급받은 페르소나 PAT으로 자기 MCP 클라이언트가 부른 도구(run 없음, 기록만)</li>
 *   <li>{@link #SYSTEM} — PAT 없는 서버 내부 기록(chat.say·credential.* 등)</li>
 * </ul>
 */
public enum AuditOrigin {
    WORKER, EXTERNAL, SYSTEM
}
