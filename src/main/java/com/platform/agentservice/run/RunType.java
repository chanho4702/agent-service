package com.platform.agentservice.run;

/**
 * TASK는 이슈 작업 자체, REVIEW는 TASK 결과를 다른 페르소나가 검증하는 run이다(P2c, 스펙 §10.4-4 —
 * 검증 없는 확정 없음). REVIEW의 종결은 새 REVIEW를 낳지 않는다(무한루프 가드). EPIC 등 상위
 * 오케스트레이션은 이후 웨이브.
 */
public enum RunType {
    TASK,
    REVIEW
}
