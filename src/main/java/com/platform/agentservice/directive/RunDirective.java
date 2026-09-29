package com.platform.agentservice.directive;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 실행 중 지시 한 건(V16, AGP-67) — 사람이 RUNNING run에 남긴 지시. 그 run의 run 토큰으로 오는 다음 MCP 도구 결과 끝에 붙어
 * 워커에게 전달되고 {@link #deliveredAt}이 채워진다(원자적 조건부 UPDATE로 한 번만 — {@link RunDirectiveRepository#markDelivered}).
 */
@Entity
@Table(name = "run_directive")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RunDirective {

    public static final int TEXT_MAX = 2000;

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false) private Long runId;
    @Column(nullable = false, length = TEXT_MAX) private String text;
    @Column(nullable = false) private Long authorMemberId;
    @CreationTimestamp @Column(nullable = false, updatable = false) private Instant createdAt;
    /** 도구 결과에 실린 시각. null = 미전달. */
    private Instant deliveredAt;

    public static RunDirective of(long runId, String text, long authorMemberId) {
        RunDirective d = new RunDirective();
        d.runId = runId;
        d.text = text;
        d.authorMemberId = authorMemberId;
        return d;
    }
}
