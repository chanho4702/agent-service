package com.platform.agentservice.office.dto;

/** 사무실 페르소나의 부가 재실 상태(AGP-63) — 없으면 null. */
public enum PersonaPresence {
    /** 활성 run 없이 최근 5분 안에 사람용 PAT(외부 MCP)으로 도구를 부름 — 외부 CLI로 작업 중("원격 접속 중" 연출). */
    EXTERNAL
}
