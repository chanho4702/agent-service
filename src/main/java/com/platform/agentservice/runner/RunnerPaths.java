package com.platform.agentservice.runner;

/**
 * 러너 프로토콜 경로의 단일 정본(P4a) — {@code SecurityConfig}의 러너 체인 매처와 {@link RunnerAuthFilter}의 방어 검사가 같은 값을
 * 본다. 관리 API({@code /api/agent/runners} 컬렉션·{@code /{id}})는 같은 접두를 쓰지만 여기 없는 경로라 JWT 체인으로 간다.
 */
public final class RunnerPaths {

    public static final String BASE = "/api/agent/runners";
    public static final String HEARTBEAT = BASE + "/heartbeat";
    public static final String CLAIM = BASE + "/claim";
    public static final String HARNESS = BASE + "/harness";
    public static final String RUNS = BASE + "/runs";
    public static final String RUNS_SUBPATHS = RUNS + "/**";

    /** 체인 매처 목록 — {@link #isProtocolPath}와 같은 범위. */
    public static final String[] PROTOCOL_MATCHERS = {HEARTBEAT, CLAIM, HARNESS, RUNS, RUNS_SUBPATHS};

    private RunnerPaths() {}

    public static boolean isProtocolPath(String uri) {
        return uri != null && (uri.equals(HEARTBEAT) || uri.equals(CLAIM) || uri.equals(HARNESS)
                || uri.equals(RUNS) || uri.startsWith(RUNS + "/"));
    }
}
