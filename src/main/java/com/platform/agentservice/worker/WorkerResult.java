package com.platform.agentservice.worker;

import java.math.BigDecimal;

/**
 * {@link WorkerLauncher#launch} 결과(P2a T3) — 순수 실행 결과일 뿐, run 상태 전이는 담지
 * 않는다(그건 T4 책임). {@code rawTail}은 실패 진단용으로 stdout/stderr 꼬리를 잘라 담는다
 * (성공이어도 참고용으로 채운다).
 */
public record WorkerResult(
        int exitCode,
        boolean timedOut,
        String resultText,
        String sessionId,
        BigDecimal costUsd,
        long inputTokens,
        long outputTokens,
        String model,
        String rawTail
) {

    /** clone 실패·타임아웃·비정상 종료·JSON 파싱 실패 등 "결과를 못 얻은" 경우의 공통 생성. */
    public static WorkerResult failure(int exitCode, boolean timedOut, String rawTail) {
        return new WorkerResult(exitCode, timedOut, null, null, null, 0L, 0L, null, rawTail);
    }

    public boolean succeeded() {
        return !timedOut && exitCode == 0;
    }
}
