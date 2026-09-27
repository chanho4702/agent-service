package com.platform.agentservice.runner;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Duration;
import java.time.Instant;

/**
 * 러너 한 대(V13 {@code runner}). 토큰({@code agr_}) 원문은 없다 — 해시만. 온라인 여부는 저장하지 않고 {@link #lastHeartbeatAt}으로
 * 매번 파생한다({@link #status}).
 */
@Entity
@Table(name = "runner")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Runner {

    public static final int MAX_CONCURRENCY_LIMIT = 8;

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private RunnerKind kind;
    @Column(nullable = false, length = 80) private String name;
    /** null = 플랫폼 전역(전역 관리자가 발급한 LOCAL, 또는 PLATFORM). */
    private Long projectId;
    @Column(nullable = false) private Long issuedBy;
    @Column(nullable = false, unique = true, length = 64) private String tokenHash;
    @Column(nullable = false, length = 12) private String tokenPrefix;
    @CreationTimestamp @Column(nullable = false, updatable = false) private Instant createdAt;
    private Instant revokedAt;
    private Instant lastHeartbeatAt;
    @Column(length = 40) private String runnerVersion;
    @Column(length = 80) private String os;
    @Column(nullable = false) private int maxConcurrency = 1;

    public static Runner local(String name, Long projectId, long issuedBy, String tokenHash, String tokenPrefix) {
        return create(RunnerKind.LOCAL, name, projectId, issuedBy, tokenHash, tokenPrefix);
    }

    public static Runner platform(String tokenHash, String tokenPrefix) {
        return create(RunnerKind.PLATFORM, "platform", null, 0L, tokenHash, tokenPrefix);
    }

    private static Runner create(RunnerKind kind, String name, Long projectId, long issuedBy, String tokenHash,
                                 String tokenPrefix) {
        Runner r = new Runner();
        r.kind = kind;
        r.name = name;
        r.projectId = projectId;
        r.issuedBy = issuedBy;
        r.tokenHash = tokenHash;
        r.tokenPrefix = tokenPrefix;
        return r;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    /** 멱등 — 최초 철회 시각을 유지한다. */
    public void revoke(Instant at) {
        if (revokedAt == null) {
            revokedAt = at;
        }
    }

    /** PLATFORM 부트스트랩이 같은 토큰으로 다시 기동했을 때 되살린다(env가 정본). */
    public void reactivate() {
        revokedAt = null;
    }

    public void heartbeat(Instant at, String runnerVersion, String os, Integer maxConcurrency) {
        this.lastHeartbeatAt = at;
        if (runnerVersion != null) {
            this.runnerVersion = cut(runnerVersion, 40);
        }
        if (os != null) {
            this.os = cut(os, 80);
        }
        if (maxConcurrency != null) {
            this.maxConcurrency = Math.max(1, Math.min(MAX_CONCURRENCY_LIMIT, maxConcurrency));
        }
    }

    /** claim도 살아 있다는 신호다 — heartbeat 주기 사이에 claim만 오는 러너가 오프라인으로 판정되지 않게. */
    public void touch(Instant at) {
        this.lastHeartbeatAt = at;
    }

    public RunnerStatus status(Instant now, Duration offlineAfter) {
        if (isRevoked()) {
            return RunnerStatus.REVOKED;
        }
        if (lastHeartbeatAt == null) {
            return RunnerStatus.NEVER_CONNECTED;
        }
        return lastHeartbeatAt.isBefore(now.minus(offlineAfter)) ? RunnerStatus.OFFLINE : RunnerStatus.ONLINE;
    }

    public boolean isOnline(Instant now, Duration offlineAfter) {
        return status(now, offlineAfter) == RunnerStatus.ONLINE;
    }

    private static String cut(String s, int max) {
        String trimmed = s.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    @Override
    public String toString() {
        return "Runner{id=" + id + ", kind=" + kind + ", name=" + name + ", projectId=" + projectId + "}";
    }
}
