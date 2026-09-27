package com.platform.agentservice.runner;

import com.platform.agentservice.execution.ExecutionSite;

/**
 * 러너 종류(P4a D-P4-5). PLATFORM은 비밀 없는 플랫폼 러너 컨테이너 — SERVER run만 집고 claim 응답으로 해석된 LLM 키를 받는다
 * (env {@code AGENT_PLATFORM_RUNNER_TOKEN}으로만 생기고 관리 API로는 발급 불가). LOCAL은 사용자 PC — LOCAL run만 집고 플랫폼·
 * 프로젝트 키를 절대 받지 않는다(그 PC의 구독·자기 키 — 과금 분리).
 */
public enum RunnerKind {
    PLATFORM(ExecutionSite.SERVER),
    LOCAL(ExecutionSite.LOCAL);

    /** 이 종류가 집을 수 있는 run 실행 위치. */
    public final ExecutionSite site;

    RunnerKind(ExecutionSite site) {
        this.site = site;
    }
}
