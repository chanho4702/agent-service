package com.platform.agentservice.run;

import java.util.EnumSet;
import java.util.Set;

/**
 * TASK는 이슈 작업 자체, REVIEW는 TASK 결과를 다른 페르소나가 검증하는 run이다(P2c, 스펙 §10.4-4 —
 * 검증 없는 확정 없음). REVIEW의 종결은 새 REVIEW를 낳지 않는다(무한루프 가드).
 *
 * <p>MEETING(착수/계획)·RETRO(회고)·ESCALATION(에스컬레이션)은 회의 run이다(P3b, 스펙 §6·D-P3b-1) — 같은 워커
 * 파이프라인을 타지만 코드 워크스페이스가 없고(clone 생략), 이슈를 claim하지 않으며, 산출물은 위키 회의록이다.
 *
 * <p>MANAGER는 매니저 페르소나의 보드 순찰 run이다(P3c, D-P3c-1) — 회의가 아니지만 "clone·claim 없음, pageId 필수 보고,
 * 게시판 게시"라는 회의 run 계약이 그대로 맞아서 같은 계열({@link #MEETING_TYPES})로 편입했다. 프롬프트만 다르다.
 */
public enum RunType {
    TASK,
    REVIEW,
    MEETING,
    RETRO,
    ESCALATION,
    MANAGER;

    public static final Set<RunType> MEETING_TYPES = EnumSet.of(MEETING, RETRO, ESCALATION, MANAGER);

    public boolean isMeeting() {
        return MEETING_TYPES.contains(this);
    }
}
