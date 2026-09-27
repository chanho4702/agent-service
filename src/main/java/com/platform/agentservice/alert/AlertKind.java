package com.platform.agentservice.alert;

/** 알림 지점(D-P3d-4). {@link #label}은 메일 제목 괄호 안에 들어가는 사유 요지다. */
public enum AlertKind {
    /** 인프라 실패(타임아웃·비정상 종료·self-FAILED·리뷰 무판정·회의 무보고) — 재시도 없이 즉시 BLOCKED. */
    INCIDENT("사고형 실패", true),
    /** 반려-fix를 이어갈 수 없는 계보 결함(원 작업 run·워크스페이스 소실) — BLOCKED. */
    LINEAGE_BROKEN("반려 후속 불가", true),
    /** 반려 한도 소진 — REVIEW run BLOCKED. */
    REJECT_LIMIT("반려 한도 소진", true),
    /** 반려 알림 임계 도달 — 한도 안이라 fix는 계속된다. */
    REJECT_THRESHOLD("반려 알림 임계", false);

    final String label;
    /** run이 멈췄는가 — 제목·본문 문구(중단 vs 진행 중)가 갈린다. */
    final boolean stopped;

    AlertKind(String label, boolean stopped) {
        this.label = label;
        this.stopped = stopped;
    }
}
