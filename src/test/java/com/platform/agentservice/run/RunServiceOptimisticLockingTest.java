package com.platform.agentservice.run;

import com.platform.agentservice.alert.AlertService;
import com.platform.agentservice.budget.BudgetProperties;
import com.platform.agentservice.budget.UsageLedgerRepository;
import com.platform.agentservice.client.AlmClient;
import com.platform.agentservice.client.IssueClaimSupport;
import com.platform.agentservice.client.TokenService;
import com.platform.agentservice.client.dto.CommentResponse;
import com.platform.agentservice.client.dto.IssueResponse;
import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRepository;
import com.platform.agentservice.persona.PersonaRole;
import com.platform.agentservice.worker.CommitLinkParser;
import com.platform.agentservice.worker.WorkerJob;
import com.platform.agentservice.worker.WorkerLauncher;
import com.platform.agentservice.worker.WorkerProperties;
import com.platform.agentservice.worker.WorkerResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * fix round(P2a T7, F2): 실패 → BLOCKED 승격(fail+save 뒤 block+save)이 <b>진짜 JPA {@code @Version} 낙관적 락</b>
 * 아래에서 실제로 BLOCKED에 도달하는지를 검증한다. P2a 당시엔 재시도 한도(attempt==3)에서만 이 경로를 탔고, P3d부터는
 * 사고형 즉시 중단이라 첫 실패부터 같은 두 번 저장 경로를 탄다.
 *
 * <p>순수 Mockito {@code RunServiceTest}는
 * {@code runRepository.save(any())}를 "인자를 그대로 돌려준다"로 스텁한다 — 그래서 같은
 * (버전이 갱신되지 않은) 엔티티 인스턴스로 두 번 저장해도 아무 문제가 없어 보인다. 하지만
 * 실제 Hibernate는 두 번째 저장에서 낙관적 락 충돌로 예외를 던진다({@code finishFailed}가
 * {@code run.fail()+save()} 한 뒤 반환값을 버리고, 그 "버전이 안 갱신된" 같은 인스턴스를
 * 차단 단계에 넘겨 {@code block()+save()}를 한 번 더 하기 때문 — 두 save가
 * 같은 in-memory 버전 값으로 DB를 두 번 갱신하려 든다). task-7 E2E(dev agentdb, run id=3)에서
 * 정확히 이 지점의 {@code ObjectOptimisticLockingFailureException}이 로그로 관찰됐고, 그 결과
 * run은 FAILED에 멈춘 채 BLOCKED로 승격되지 못했다(15개 run, 5회 attempt 1→2→3 반복, BLOCKED
 * 0건). 이 테스트는 {@link RunRepository}만 진짜(H2, {@code @DataJpaTest})로 두고 나머지
 * 협력자는 목업이다.
 */
@DataJpaTest
@ActiveProfiles("test")
class RunServiceOptimisticLockingTest {

    private static final long PERSONA_MEMBER_ID = 42L;
    private static final String ISSUE_KEY = "AGP-9";
    private static final String BEARER = "Bearer x";

    @Autowired RunRepository runRepository;
    @Autowired PersonaRepository personaRepository;
    @Autowired UsageLedgerRepository usageLedgerRepository;

    private RunService runService;
    private Persona persona;

    @BeforeEach
    void setUp() {
        persona = personaRepository.save(Persona.of(PERSONA_MEMBER_ID, "jiho", PersonaRole.BACKEND, "지호", "🔧", null));

        AlmClient almClient = Mockito.mock(AlmClient.class);
        IssueClaimSupport issueClaimSupport = Mockito.mock(IssueClaimSupport.class);
        TokenService tokenService = Mockito.mock(TokenService.class);
        WorkerLauncher workerLauncher = Mockito.mock(WorkerLauncher.class);
        CommitLinkParser commitLinkParser = Mockito.mock(CommitLinkParser.class);

        IssueResponse issue = new IssueResponse(1L, ISSUE_KEY, 1L, "제목", "본문", "task", "inprogress", "high",
                PERSONA_MEMBER_ID, 1L, null, null, null, null, null, null, List.of(), List.of(), 0L, 1, null, null, null);
        when(tokenService.bearerFor(anyLong())).thenReturn(BEARER);
        when(commitLinkParser.parse(any())).thenReturn(List.of());
        when(issueClaimSupport.claim(anyString(), anyLong(), anyString(), anyString())).thenReturn(issue);
        when(almClient.comments(anyLong(), anyString())).thenReturn(List.of());
        when(almClient.getByKey(anyString(), anyString())).thenReturn(issue);
        when(almClient.addComment(anyLong(), anyString(), anyString()))
                .thenReturn(new CommentResponse(1L, 1L, PERSONA_MEMBER_ID, "b", null, null));
        // 워커가 실패로 죽었다고 가정 — 사고형 즉시 중단(BLOCKED 승격) 경로를 타게 한다.
        when(workerLauncher.launch(any(Run.class), any(WorkerJob.class)))
                .thenReturn(WorkerResult.failure(1, false, "boom", null));

        WorkerProperties workerProperties = new WorkerProperties(
                "C:\\agent-work", "C:\\bundle", List.of(), "claude", 80, 40,
                "Read,Edit,Write", "http://localhost/api/agent/mcp", Map.of("AGP", "https://example.com/agp.git"), List.of());
        SchedulerProperties schedulerProperties = new SchedulerProperties(true, 60000L, 2, 1, "jiho", 3);
        BudgetProperties budgetProperties = new BudgetProperties(new BigDecimal("100"), new BigDecimal("5"));
        BudgetGuard budgetGuard = Mockito.mock(BudgetGuard.class);
        ReviewService reviewService = Mockito.mock(ReviewService.class);
        MeetingService meetingService = Mockito.mock(MeetingService.class);
        AlertService alertService = Mockito.mock(AlertService.class);
        when(budgetGuard.allow(anyLong())).thenReturn(true);

        runService = new RunService(runRepository, almClient, issueClaimSupport, tokenService, personaRepository,
                workerLauncher, workerProperties, usageLedgerRepository, schedulerProperties, budgetProperties,
                commitLinkParser, budgetGuard, reviewService, meetingService, alertService);
    }

    @AfterEach
    void cleanUp() {
        // NOT_SUPPORTED라 이 클래스는 @DataJpaTest 기본 롤백 혜택을 못 받는다(아래 테스트
        // javadoc 참고) — 다른 @DataJpaTest 클래스와 스프링 테스트 컨텍스트(=같은 임베디드
        // H2)를 캐시 공유할 수 있으므로 직접 치운다. run(자식)을 persona(부모)보다 먼저 지운다(FK).
        runRepository.deleteAll();
        personaRepository.deleteAll();
    }

    /**
     * {@code propagation = NOT_SUPPORTED}가 핵심이다: {@code @DataJpaTest}는 기본적으로 테스트
     * 메서드 전체를 트랜잭션 하나로 감싸고 끝에 롤백한다 — 그 안에서는 {@code RunRepository}의
     * 모든 호출이 "같은" 영속성 컨텍스트에 참여(join)해 엔티티가 계속 managed 상태로 남고,
     * dirty checking이 알아서 처리해 버려서 실제 운영에서 나는 낙관적 락 충돌이 재현되지
     * 않는다(이 테스트를 처음 NOT_SUPPORTED 없이 돌렸을 때 그린으로 통과해 버려 확인함).
     * 운영 {@code RunService}는 메서드 전체를 감싸는 트랜잭션이 없다 — {@code findById}/
     * {@code save} 각각이 Spring Data JPA 기본값({@code SimpleJpaRepository}가 각 메서드에
     * 붙이는 {@code @Transactional})으로 매번 독립된 트랜잭션을 커밋하고 엔티티를 detach한다.
     * {@code NOT_SUPPORTED}로 이 테스트의 앰비언트 트랜잭션을 꺼야 그 진짜 조건(호출마다
     * 커밋+detach)이 재현된다.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void first_failure_actually_lands_on_blocked_under_real_optimistic_locking() {
        Run run = Run.queued(RunType.TASK, ISSUE_KEY, 1L, persona.getId(), RunTrigger.SCHEDULER, "harness://default", null);
        run = runRepository.save(run);

        // 이 호출이 실제 Hibernate @Version 아래서 예외 없이 끝나야 하고(수정 전에는
        // ObjectOptimisticLockingFailureException을 던졌다 — 같은 stale 인스턴스로 두 번
        // save를 시도했기 때문), 최종 상태가 BLOCKED여야 한다(FAILED에 멈추면 안 된다).
        runService.execute(run.getId());

        Run reloaded = runRepository.findById(run.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(RunStatus.BLOCKED);
        assertThat(reloaded.getError()).contains("사고형 실패 — 즉시 중단: boom");
        // continuation 행이 생기지 않는다 — 재시도가 없다.
        assertThat(runRepository.count()).isEqualTo(1);
    }

    /** V7 reject_count가 실제 매핑으로 저장·재조회되고, 사람 재개 continuation에 승계된다(D-P3d-2). */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void reject_count_round_trips_through_the_database_and_survives_resume() {
        Run task = Run.queued(RunType.TASK, ISSUE_KEY, 1L, persona.getId(), RunTrigger.SCHEDULER, "harness://default", null);
        task.start("C:\\agent-work\\run-1", null);
        task.complete();
        task = runRepository.save(task);
        Run fix = runRepository.save(Run.fixContinuation(task, 99L, 2));

        Run reloaded = runRepository.findById(fix.getId()).orElseThrow();
        assertThat(reloaded.getRejectCount()).isEqualTo(2);

        reloaded.start("pending", null);
        reloaded.block("사고형 실패 — 즉시 중단: x");
        reloaded = runRepository.save(reloaded);
        Run resumed = runRepository.save(Run.continuation(reloaded));
        assertThat(runRepository.findById(resumed.getId()).orElseThrow().getRejectCount()).isEqualTo(2);
    }
}
