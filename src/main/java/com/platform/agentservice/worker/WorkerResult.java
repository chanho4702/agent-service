package com.platform.agentservice.worker;

import java.math.BigDecimal;

/**
 * {@link WorkerLauncher#launch} 결과(P2a T3) — 순수 실행 결과일 뿐, run 상태 전이는 담지
 * 않는다(그건 T4 책임). {@code rawTail}은 실패 진단용으로 stdout/stderr 꼬리를 잘라 담는다
 * (성공이어도 참고용으로 채운다).
 *
 * <p>{@code workspacePath}는 T6b가 추가했다 — {@code Run.workspacePath}(DB 컬럼)는
 * {@code RunService.execute}가 launch() 호출 "전"에 {@code "pending"} 자리표시자로 커밋해
 * 두고 이후 갱신하지 않으므로(T4 확정 결정, 실제 로컬 경로는 이 서비스가 참조할 일이 없다는
 * 전제였다) 실제 워크스페이스 경로를 담지 못한다. {@link WorkerLauncher#prepareWorkspaceDir}는
 * run id에 경합 발생 시 {@code -2}, {@code -3}... 접미사를 붙이므로 run id만으로 경로를
 * 재구성하는 것도 신뢰할 수 없다 — 그래서 launch()가 실제로 만든 경로를 결과에 실어 호출자
 * (커밋 파서)에게 직접 전달하는 것이 가장 단순한 이음매다(엔티티 변경 없음, T6b 결정).
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
        String rawTail,
        String workspacePath
) {

    /** clone 실패·타임아웃·비정상 종료·JSON 파싱 실패 등 "결과를 못 얻은" 경우의 공통 생성 — 워크스페이스 경로 없음. */
    public static WorkerResult failure(int exitCode, boolean timedOut, String rawTail) {
        return new WorkerResult(exitCode, timedOut, null, null, null, 0L, 0L, null, rawTail, null);
    }

    /** 위와 같되 워크스페이스가 실제로 만들어진 뒤의 실패(clone 실패 등)에 경로를 함께 싣는다. */
    public static WorkerResult failure(int exitCode, boolean timedOut, String rawTail, String workspacePath) {
        return new WorkerResult(exitCode, timedOut, null, null, null, 0L, 0L, null, rawTail, workspacePath);
    }

    public boolean succeeded() {
        return !timedOut && exitCode == 0;
    }
}
