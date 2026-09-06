package com.platform.agentservice.run;

/**
 * 프로젝트 예산 확인 훅(P2a T4가 정의, T5 {@code BudgetService}가 실제 구현으로 교체).
 * 이 태스크는 Dispatcher가 새 이슈를 픽업하기 전에 호출할 자리만 만들어 둔다 — 실제
 * 예산 집계/한도 판정 로직은 이 인터페이스의 책임이 아니다.
 */
public interface BudgetGuard {

    /** true면 해당 프로젝트에 새 run을 시작해도 된다. */
    boolean allow(long projectId);
}
