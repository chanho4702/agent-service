package com.platform.agentservice.run;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 리뷰 run 상시화 설정(P2c T3, AGP-44) — {@code platform.agent.review.*}.
 *
 * <p>{@link #enabled()} 기본값은 true다 — 스펙 §10.4-4 "검증 없는 확정 없음"이 기본 동작이어야 하고,
 * 끄는 것은 레거시(워커가 스스로 done 전환) 복원이라는 명시적 선택이어야 한다.
 * {@link #personaSlug()}가 비어 있거나 가리키는 페르소나가 없으면 리뷰를 건너뛰는 게 아니라
 * 이슈를 미확정으로 남기고 경고한다(D-P2c-3 fail-closed) — {@link ReviewService} 참고.
 * {@link #model()}이 비면 자동화 모델 정책({@link SchedulerProperties#modelFor})을 따른다(D-P2c-5).
 */
@ConfigurationProperties(prefix = "platform.agent.review")
public record ReviewProperties(
        @DefaultValue("true") boolean enabled,
        String personaSlug,
        String model
) {
}
