package com.platform.agentservice.run;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 리뷰 run 상시화 설정(P2c T3, AGP-44) — {@code platform.agent.review.*}.
 *
 * <p>{@link #enabled()} 기본값은 true다 — 스펙 §10.4-4 "검증 없는 확정 없음"이 기본 동작이어야 하고,
 * 끄는 것은 레거시(워커가 스스로 done 전환) 복원이라는 명시적 선택이어야 한다.
 * {@link #personaSlug()}는 P4b(D-P4b-1)부터 폴백이다 — 리뷰어는 AI 팀 설정(DB, 프로젝트 &gt; 전역)이 먼저이고, 그다음 이 값,
 * 그다음 자동 선택이다({@code ReviewerResolver}). 어느 단계로도 리뷰어가 없으면 리뷰를 건너뛰는 게 아니라
 * 이슈를 미확정으로 남기고 경고한다(D-P2c-3 fail-closed) — {@link ReviewService} 참고.
 * {@link #model()}이 비면 자동화 모델 정책({@link SchedulerProperties#modelFor})을 따른다(D-P2c-5).
 *
 * <p><b>반려 예산(P3d, D-P3d-2)</b>: {@link #rejectMax()}는 한 작업 계보가 자동 수정(반려-fix)을 받는 최대 횟수다 — 이
 * 횟수를 넘는 반려는 fix 대신 REVIEW run을 BLOCKED로 올린다. {@link #rejectAlertAt()}은 알림만 보내는 임계(진행은 계속)이며
 * 비우면 {@link #rejectMax()}와 같다. 인프라 재시도 한도와 섞지 않는다 — 사고형 실패는 재시도 없이 즉시 중단이다.
 */
@ConfigurationProperties(prefix = "platform.agent.review")
public record ReviewProperties(
        boolean enabled,
        String personaSlug,
        String model,
        int rejectMax,
        Integer rejectAlertAt
) {

    public static final int DEFAULT_REJECT_MAX = 2;

    @ConstructorBinding
    public ReviewProperties(@DefaultValue("true") boolean enabled, String personaSlug, String model,
                            @DefaultValue("2") int rejectMax, Integer rejectAlertAt) {
        this.enabled = enabled;
        this.personaSlug = personaSlug;
        this.model = model;
        // 음수를 받아들이면 "반려 = 즉시 BLOCKED"(0)보다 더 엄격한 의미가 없어 설정 실수만 숨긴다.
        this.rejectMax = Math.max(0, rejectMax);
        this.rejectAlertAt = rejectAlertAt;
    }

    /** 반려 예산을 따로 정하지 않는 설정(기존 호출부·테스트) — 기본 한도 2, 알림 임계 = 한도. */
    public ReviewProperties(boolean enabled, String personaSlug, String model) {
        this(enabled, personaSlug, model, DEFAULT_REJECT_MAX, null);
    }

    /** 알림 임계 — 미설정이거나 1 미만이면 한도와 같다(반려 0회에 알림은 의미가 없다). */
    public int effectiveRejectAlertAt() {
        return rejectAlertAt == null || rejectAlertAt < 1 ? rejectMax : rejectAlertAt;
    }
}
