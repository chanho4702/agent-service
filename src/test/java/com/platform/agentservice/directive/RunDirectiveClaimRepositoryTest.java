package com.platform.agentservice.directive;

import com.platform.agentservice.audit.AuditService;
import com.platform.agentservice.run.Run;
import com.platform.agentservice.run.RunRepository;
import com.platform.agentservice.run.RunTrigger;
import com.platform.agentservice.run.RunType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실행 중 지시 전달의 원자성(AGP-67) — 실제 PostgreSQL(Testcontainers)에서 같은 run의 미전달 지시를 두 도구 호출이 동시에 가져간다.
 * 증명 대상: 두 호출이 받는 집합은 서로소이고 합치면 전부(한 지시가 두 번 전달되거나 사라지지 않는다). H2가 아니라 PG인 이유는
 * {@code RunnerClaimRepositoryTest}와 같다 — 운영 DB의 행 잠금·재평가 아래서 성립해야 한다.
 */
@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({RunDirectiveService.class, AuditService.class})
class RunDirectiveClaimRepositoryTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @Autowired RunRepository runRepository;
    @Autowired RunDirectiveRepository directiveRepository;
    @Autowired RunDirectiveService directiveService;

    @AfterEach
    void cleanUp() {
        directiveRepository.deleteAll();
        runRepository.deleteAll();
    }

    private Run runningRun(String issueKey) {
        Run run = Run.queued(RunType.TASK, issueKey, 1L, 5L, RunTrigger.USER, "harness://default", null);
        run.start("pending", null);
        return runRepository.save(run);
    }

    private static Set<Long> ids(List<RunDirective> directives) {
        Set<Long> ids = new HashSet<>();
        directives.forEach(d -> ids.add(d.getId()));
        return ids;
    }

    @Test
    void concurrent_claims_on_one_run_get_disjoint_sets_that_cover_everything() throws Exception {
        for (int round = 0; round < 5; round++) {
            Run run = runningRun("AGP-" + (100 + round));
            Set<Long> all = new HashSet<>();
            for (int i = 0; i < 30; i++) {
                all.add(directiveRepository.save(RunDirective.of(run.getId(), "지시 " + i, 7L)).getId());
            }
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            Callable<List<RunDirective>> claim = () -> {
                start.await();
                return directiveService.claimUndelivered(run.getId());
            };
            Future<List<RunDirective>> a = pool.submit(claim);
            Future<List<RunDirective>> b = pool.submit(claim);
            start.countDown();
            Set<Long> first = ids(a.get(30, TimeUnit.SECONDS));
            Set<Long> second = ids(b.get(30, TimeUnit.SECONDS));
            pool.shutdown();

            assertThat(java.util.Collections.disjoint(first, second)).as("두 호출이 받은 지시 %s / %s", first, second).isTrue();
            Set<Long> union = new HashSet<>(first);
            union.addAll(second);
            assertThat(union).isEqualTo(all);
            assertThat(directiveRepository.findByRunIdAndDeliveredAtIsNullOrderByIdAsc(run.getId())).isEmpty();
            assertThat(directiveService.claimUndelivered(run.getId())).isEmpty();
        }
    }

    @Test
    void claim_is_scoped_to_the_run_and_ordered_oldest_first() {
        Run mine = runningRun("AGP-1");
        Run other = runningRun("AGP-2");
        RunDirective d1 = directiveRepository.save(RunDirective.of(mine.getId(), "첫째", 7L));
        RunDirective d2 = directiveRepository.save(RunDirective.of(mine.getId(), "둘째", 7L));
        RunDirective foreign = directiveRepository.save(RunDirective.of(other.getId(), "남의 것", 7L));

        assertThat(directiveService.claimUndelivered(mine.getId())).extracting(RunDirective::getId)
                .containsExactly(d1.getId(), d2.getId());
        assertThat(directiveRepository.findById(foreign.getId()).orElseThrow().getDeliveredAt()).isNull();
        assertThat(directiveService.pendingCounts(List.of(mine.getId(), other.getId())))
                .containsOnlyKeys(other.getId()).containsEntry(other.getId(), 1);
    }

    /** 워커가 report_result·request_gate로 막 멈춘 run의 결과에는 싣지 않는다 — 읽을 워커가 없는데 "전달됨"이 되면 안 된다. */
    @Test
    void directives_of_a_run_that_is_no_longer_running_stay_pending() {
        Run run = runningRun("AGP-3");
        directiveRepository.save(RunDirective.of(run.getId(), "지시", 7L));
        Run reloaded = runRepository.findById(run.getId()).orElseThrow();
        reloaded.parkForApproval();
        runRepository.save(reloaded);

        assertThat(directiveService.claimUndelivered(run.getId())).isEmpty();
        assertThat(directiveService.pendingCounts(List.of(run.getId()))).containsEntry(run.getId(), 1);
    }
}
