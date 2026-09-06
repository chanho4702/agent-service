package com.platform.agentservice.run;

import com.platform.agentservice.client.AlmClient;
import com.platform.agentservice.client.TokenService;
import com.platform.agentservice.client.dto.IssuePageResponse;
import com.platform.agentservice.client.dto.IssueResponse;
import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 무인 자동화 루프의 심장(P2a T4) — {@code platform.agent.scheduler.enabled=true}일 때만
 * {@link org.springframework.scheduling.annotation.EnableScheduling}이 등록되므로
 * {@link #tick} 자체가 스케줄러에 걸리지 않는다({@code SchedulerEnablementConfig} 참고).
 * 그와 별개로 {@link #tick} 안에서도 {@code enabled} 킬스위치를 한 번 더 본다 — 런타임에
 * 끄고 켤 수 있는 값이 아니라(재기동 필요) 방어적 이중 확인일 뿐이다.
 *
 * <p>매 틱마다 두 가지를 한다: (1) 이미 QUEUED인 run(재시도로 만들어진 continuation
 * 포함)을 드레인해서 실행시키고, (2) 전역 동시성 한도에 여유가 있으면 라벨 {@code auto}·
 * 상태 {@code todo}인 ALM 이슈 중 활성 run이 없는 것 하나를 새로 픽업한다(한 틱에 하나만
 * — 단순화, 브리핑 지시). 프로젝트별 동시성 한도·{@link BudgetGuard}는 픽업 단계에서만
 * 본다 — 드레인은 이미 결정된 작업을 실행에 옮기는 것뿐이라 다시 게이트하지 않는다.
 */
@Slf4j
@Component
public class Dispatcher {

    /** "새 이슈 픽업" 동시성 상한 판정에서만 쓴다 — WAITING_APPROVAL은 사람 대기라 워커 자리를 차지하지 않는다. */
    private static final Set<RunStatus> CONCURRENCY_STATUSES = EnumSet.of(RunStatus.QUEUED, RunStatus.RUNNING);
    private static final List<String> AUTO_LABEL = List.of("auto");
    private static final List<String> TODO_STATUS = List.of("todo");

    private final SchedulerProperties properties;
    private final BudgetGuard budgetGuard;
    private final RunRepository runRepository;
    private final RunService runService;
    private final AlmClient almClient;
    private final TokenService tokenService;
    private final PersonaRepository personaRepository;

    public Dispatcher(SchedulerProperties properties, BudgetGuard budgetGuard, RunRepository runRepository,
                       RunService runService, AlmClient almClient, TokenService tokenService,
                       PersonaRepository personaRepository) {
        this.properties = properties;
        this.budgetGuard = budgetGuard;
        this.runRepository = runRepository;
        this.runService = runService;
        this.almClient = almClient;
        this.tokenService = tokenService;
        this.personaRepository = personaRepository;
    }

    @Scheduled(fixedDelayString = "${platform.agent.scheduler.interval-ms:60000}")
    public void tick() {
        if (!properties.enabled()) {
            return;
        }
        drainQueued();
        pickNewIssue();
    }

    /** 이미 QUEUED인 run(최초 대기분 + 재시도 continuation)을 전부 실행시킨다. */
    private void drainQueued() {
        for (Run run : runRepository.findByStatus(RunStatus.QUEUED)) {
            runService.execute(run.getId());
        }
    }

    /** 전역 동시성 한도 → 기본 페르소나 해석 → 라벨/상태로 이슈 검색 → 프로젝트별 한도·예산·중복 체크 순. */
    private void pickNewIssue() {
        if (runRepository.countByStatusIn(CONCURRENCY_STATUSES) >= properties.maxConcurrentGlobal()) {
            return;
        }

        Persona persona = personaRepository.findBySlug(properties.defaultPersonaSlug()).orElse(null);
        if (persona == null) {
            log.warn("기본 페르소나를 찾을 수 없어 자동 픽업을 건너뜁니다: slug={}", properties.defaultPersonaSlug());
            return;
        }

        String bearer = tokenService.bearerFor(persona.getMemberId());
        IssuePageResponse page = almClient.search(null, null, TODO_STATUS, null, AUTO_LABEL, 0, bearer);

        for (IssueResponse issue : page.items()) {
            if (runRepository.existsByIssueKeyAndStatusIn(issue.key(), RunService.ACTIVE_STATUSES)) {
                continue;
            }
            if (!budgetGuard.allow(issue.projectId())) {
                continue;
            }
            if (runRepository.countByStatusInAndProjectId(CONCURRENCY_STATUSES, issue.projectId())
                    >= properties.maxConcurrentPerProject()) {
                continue;
            }

            try {
                Run run = runService.createQueuedForIssue(
                        new RunService.IssueRef(issue.key(), issue.projectId(), persona.getId()),
                        RunTrigger.SCHEDULER, null);
                runService.execute(run.getId());
            } catch (Exception e) {
                log.warn("이슈 픽업 실패, 다음 틱에 재시도합니다: issueKey={} error={}", issue.key(), e.getMessage());
            }
            return; // 한 틱에 하나만 픽업한다(단순화)
        }
    }
}
