package com.platform.agentservice.runner;

import com.platform.agentservice.execution.ExecutionProperties;
import com.platform.agentservice.execution.ExecutionSite;
import com.platform.agentservice.run.Run;
import com.platform.agentservice.run.RunStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * "러너 대기" 판정(D-P4-4) — 사무실이 폴링마다 한 번 스냅샷을 떠서 run마다 판정한다(러너는 소수라 쿼리 1회).
 *
 * <p>QUEUED run이 지금 아무도 집어갈 수 없는 상태인가:
 * <ul>
 *   <li>LOCAL: 고정 러너가 있으면 그 러너가 온라인인지, 없으면 범위(그 프로젝트 또는 플랫폼 전역)의 LOCAL 러너가 하나라도 온라인인지.</li>
 *   <li>SERVER: 인프로세스 실행이 켜져 있으면 대기가 아니다(서비스가 직접 돈다). 꺼져 있으면(compose) 고정 러너 또는 PLATFORM 러너가 온라인인지.</li>
 * </ul>
 */
@Component
public class RunnerAvailability {

    private final RunnerRepository runnerRepository;
    private final RunnerProperties runnerProperties;
    private final ExecutionProperties executionProperties;
    private final Clock clock;

    @Autowired
    public RunnerAvailability(RunnerRepository runnerRepository, RunnerProperties runnerProperties,
                              ExecutionProperties executionProperties) {
        this(runnerRepository, runnerProperties, executionProperties, Clock.systemUTC());
    }

    RunnerAvailability(RunnerRepository runnerRepository, RunnerProperties runnerProperties,
                       ExecutionProperties executionProperties, Clock clock) {
        this.runnerRepository = runnerRepository;
        this.runnerProperties = runnerProperties;
        this.executionProperties = executionProperties;
        this.clock = clock;
    }

    public Snapshot snapshot() {
        Instant since = clock.instant().minus(runnerProperties.offlineAfter());
        return new Snapshot(runnerRepository.findByRevokedAtIsNullAndLastHeartbeatAtAfter(since),
                executionProperties.inProcessEnabled());
    }

    /** 온라인 러너 목록 + 인프로세스 여부. */
    public record Snapshot(List<Runner> online, boolean inProcess) {

        public static Snapshot empty(boolean inProcess) {
            return new Snapshot(List.of(), inProcess);
        }

        public boolean awaitingRunner(Run run) {
            if (run == null || run.getStatus() != RunStatus.QUEUED) {
                return false;
            }
            if (run.getRunnerId() != null) {
                return online.stream().noneMatch(r -> r.getId().equals(run.getRunnerId()));
            }
            if (run.getExecutionSite() == ExecutionSite.SERVER) {
                return !inProcess && online.stream().noneMatch(r -> r.getKind() == RunnerKind.PLATFORM);
            }
            return online.stream().noneMatch(r -> r.getKind() == RunnerKind.LOCAL
                    && (r.getProjectId() == null || r.getProjectId().equals(run.getProjectId())));
        }
    }
}
