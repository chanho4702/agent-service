package com.platform.agentservice.review;

/** 유효 리뷰어가 어디서 왔는지(D-P4b-1) — 화면 안내와 경고용. NONE이면 TASK DONE 뒤 검증 run을 띄울 수 없다. */
public enum ReviewerSource {
    PROJECT,
    PLATFORM,
    ENV,
    AUTO,
    NONE
}
