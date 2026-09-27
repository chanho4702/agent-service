package com.platform.agentservice.run;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 회의 run 설정(P3b, D-P3b-4·5) — {@code platform.agent.meetings.*}.
 *
 * <p>{@link #spaceId()}는 회의록을 쓰는 위키 스페이스다. 비어 있으면 회의록을 남길 곳이 없으므로 소집 API는 400,
 * 회고 cron·자동 에스컬레이션은 건너뛴다(회의록 없는 회의는 "보고서 없는 완료"다).
 * {@link #retroCron()}은 Spring cron 6필드(Asia/Seoul) — 비우면 회고 주기 실행이 꺼진다. {@link #managerCron()}(P3c)은
 * 매니저 순찰 주기로 같은 형식·같은 규칙이다.
 * {@link #autoEscalation()} 기본 false는 비용 때문이다 — BLOCKED마다 회의 run이 하나씩 붙는다.
 * {@link #autoIssue()} 기본 true는 스펙 §6 "결정→이슈 생성은 프로젝트 설정으로 자동 허용 가능"의 기본값이다 —
 * false면 워커는 이슈를 직접 만들지 않고 PLAN 게이트로 사람 승인을 받는다.
 */
@ConfigurationProperties(prefix = "platform.agent.meetings")
public record MeetingProperties(
        Long spaceId,
        String retroCron,
        @DefaultValue("false") boolean autoEscalation,
        @DefaultValue("true") boolean autoIssue,
        String managerCron
) {

    /** 생성자가 여럿이라 바인딩 대상을 명시해야 한다 — 없으면 Spring Boot가 어느 쪽으로 바인딩할지 정하지 못한다. */
    @ConstructorBinding
    public MeetingProperties {
    }

    /** 매니저 cron 없이 쓰는 기존 호출부용(P3b 시그니처). */
    public MeetingProperties(Long spaceId, String retroCron, boolean autoEscalation, boolean autoIssue) {
        this(spaceId, retroCron, autoEscalation, autoIssue, null);
    }

    public boolean hasSpace() {
        return spaceId != null && spaceId > 0;
    }

    public boolean retroEnabled() {
        return retroCron != null && !retroCron.isBlank();
    }

    public boolean managerEnabled() {
        return managerCron != null && !managerCron.isBlank();
    }
}
