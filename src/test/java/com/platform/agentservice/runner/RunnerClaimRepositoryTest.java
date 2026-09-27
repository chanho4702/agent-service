package com.platform.agentservice.runner;

import com.platform.agentservice.execution.ExecutionSite;
import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRepository;
import com.platform.agentservice.persona.PersonaRole;
import com.platform.agentservice.run.Run;
import com.platform.agentservice.run.RunRepository;
import com.platform.agentservice.run.RunStatus;
import com.platform.agentservice.run.RunTrigger;
import com.platform.agentservice.run.RunType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 러너 배정 원자성·범위(P4a AGP-69) — 실제 PostgreSQL(Testcontainers)에서 조건부 UPDATE를 동시에 두 번 때린다. H2가 아니라 PG인 이유:
 * 운영 DB의 행 잠금·MVCC 아래서 "두 러너가 같은 run을 동시에 집으면 한쪽만 1행"이 성립하는지가 증명 대상이다.
 *
 * <p>{@code NOT_SUPPORTED}: {@code @DataJpaTest} 기본 트랜잭션 안에서는 두 스레드가 커밋된 행을 보지 못한다(RunServiceOptimisticLockingTest와 같은 이유).
 */
@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RunnerClaimRepositoryTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // create-drop이면 컨테이너가 먼저 내려간 뒤 종료 훅의 drop이 연결을 30초 기다린다 — 컨테이너째 버리므로 create면 충분하다.
        r.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @Autowired RunRepository runRepository;
    @Autowired PersonaRepository personaRepository;

    private long personaId;

    @BeforeEach
    void setUp() {
        personaId = personaRepository.save(Persona.of(42L, "jiho", PersonaRole.BACKEND, "지호", "🔧", null)).getId();
    }

    @AfterEach
    void cleanUp() {
        runRepository.deleteAll();
        personaRepository.deleteAll();
    }

    private Run queued(long projectId, ExecutionSite site, Long pinnedRunner) {
        Run run = Run.queued(RunType.TASK, "AGP-" + System.nanoTime() % 100000, projectId, personaId,
                RunTrigger.USER, "harness://default", null);
        run.assignExecutionSite(site);
        if (pinnedRunner != null) {
            ReflectionTestUtils.setField(run, "runnerId", pinnedRunner);
        }
        return runRepository.save(run);
    }

    private int claim(long runId, long runnerId, ExecutionSite site) {
        return runRepository.claimForRunner(runId, runnerId, site, RunStatus.QUEUED, RunStatus.RUNNING, "pending",
                Instant.now());
    }

    @Test
    void two_concurrent_claims_of_the_same_run_exactly_one_wins() throws Exception {
        for (int round = 0; round < 10; round++) {
            long runId = queued(1L, ExecutionSite.LOCAL, null).getId();
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Callable<Integer> byRunner1 = () -> {
                    start.await();
                    return claim(runId, 1L, ExecutionSite.LOCAL);
                };
                Callable<Integer> byRunner2 = () -> {
                    start.await();
                    return claim(runId, 2L, ExecutionSite.LOCAL);
                };
                Future<Integer> a = pool.submit(byRunner1);
                Future<Integer> b = pool.submit(byRunner2);
                start.countDown();
                int first = a.get(20, TimeUnit.SECONDS);
                int second = b.get(20, TimeUnit.SECONDS);

                assertThat(first + second).as("round %d — 둘 중 정확히 하나만 1행을 얻어야 한다", round).isEqualTo(1);
                Run claimed = runRepository.findById(runId).orElseThrow();
                assertThat(claimed.getStatus()).isEqualTo(RunStatus.RUNNING);
                assertThat(claimed.getRunnerId()).isEqualTo(first == 1 ? 1L : 2L);
                assertThat(claimed.getWorkspacePath()).isEqualTo("pending");
                assertThat(claimed.getStartedAt()).isNotNull();
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void claim_bumps_version_so_in_process_optimistic_save_of_a_stale_copy_would_conflict() {
        Run run = queued(1L, ExecutionSite.SERVER, null);
        long before = run.getVersion();
        assertThat(claim(run.getId(), 7L, ExecutionSite.SERVER)).isEqualTo(1);
        assertThat(runRepository.findById(run.getId()).orElseThrow().getVersion()).isGreaterThan(before);
    }

    @Test
    void project_runner_candidates_exclude_other_projects_and_other_sites() {
        Run mine = queued(1L, ExecutionSite.LOCAL, null);
        Run otherProject = queued(2L, ExecutionSite.LOCAL, null);
        Run serverSite = queued(1L, ExecutionSite.SERVER, null);

        List<Long> inProject = runRepository.findClaimCandidatesInProject(RunStatus.QUEUED, ExecutionSite.LOCAL, 1L, 5L,
                PageRequest.of(0, 20));
        assertThat(inProject).containsExactly(mine.getId());

        List<Long> global = runRepository.findClaimCandidates(RunStatus.QUEUED, ExecutionSite.LOCAL, 5L, PageRequest.of(0, 20));
        assertThat(global).containsExactly(mine.getId(), otherProject.getId());

        List<Long> server = runRepository.findClaimCandidates(RunStatus.QUEUED, ExecutionSite.SERVER, 5L, PageRequest.of(0, 20));
        assertThat(server).containsExactly(serverSite.getId());

        // 위치가 다르면 조건부 UPDATE 자체가 0행 — 후보 조회를 건너뛴 직접 배정도 막힌다.
        assertThat(claim(serverSite.getId(), 5L, ExecutionSite.LOCAL)).isZero();
    }

    @Test
    void run_pinned_to_another_runner_cannot_be_claimed() {
        Run pinned = queued(1L, ExecutionSite.LOCAL, 9L);

        assertThat(runRepository.findClaimCandidates(RunStatus.QUEUED, ExecutionSite.LOCAL, 5L, PageRequest.of(0, 20)))
                .doesNotContain(pinned.getId());
        assertThat(claim(pinned.getId(), 5L, ExecutionSite.LOCAL)).isZero();

        assertThat(runRepository.findClaimCandidates(RunStatus.QUEUED, ExecutionSite.LOCAL, 9L, PageRequest.of(0, 20)))
                .contains(pinned.getId());
        assertThat(claim(pinned.getId(), 9L, ExecutionSite.LOCAL)).isEqualTo(1);
    }

    @Test
    void result_marker_is_set_once_and_only_by_the_assigned_runner() {
        Run run = queued(1L, ExecutionSite.LOCAL, null);
        assertThat(claim(run.getId(), 3L, ExecutionSite.LOCAL)).isEqualTo(1);

        assertThat(runRepository.markRunnerResult(run.getId(), 4L, Instant.now())).as("다른 러너").isZero();
        assertThat(runRepository.markRunnerResult(run.getId(), 3L, Instant.now())).as("첫 보고").isEqualTo(1);
        assertThat(runRepository.markRunnerResult(run.getId(), 3L, Instant.now())).as("중복 보고").isZero();
    }
}
