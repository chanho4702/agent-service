package com.platform.agentservice.persona;

public enum PersonaRole {
    PLANNER, DESIGNER, FRONTEND, BACKEND, OPS, REVIEWER,
    /**
     * 스펙 §10.4-3 관리 에이전트 — 보드 정리·독려·보고. 실무 6롤과 달리 이슈를 claim하지 않는다(P3c, AGP-45).
     * 회의 기본 참석 규칙이 롤 순서(ordinal)로 정렬하므로 실무 롤 뒤에 둔다.
     */
    MANAGER
}
