package com.platform.agentservice.office;

import com.platform.agentservice.audit.AuditStatus;
import com.platform.agentservice.audit.ToolCallAudit;
import com.platform.agentservice.audit.ToolCallAuditRepository;
import com.platform.agentservice.budget.BudgetService;
import com.platform.agentservice.chat.ChatAvailability;
import com.platform.agentservice.budget.LedgerScope;
import com.platform.agentservice.budget.UsageLedger;
import com.platform.agentservice.budget.UsageLedgerRepository;
import com.platform.agentservice.office.dto.OfficeResponse;
import com.platform.agentservice.office.dto.PersonaActivityResponse;
import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRepository;
import com.platform.agentservice.persona.PersonaRole;
import com.platform.agentservice.run.Gate;
import com.platform.agentservice.run.GateKind;
import com.platform.agentservice.run.GateRepository;
import com.platform.agentservice.run.MeetingProperties;
import com.platform.agentservice.run.Run;
import com.platform.agentservice.run.RunRepository;
import com.platform.agentservice.run.RunStatus;
import com.platform.agentservice.run.RunTrigger;
import com.platform.agentservice.run.RunType;
import com.platform.common.error.NotFoundException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * {@link OfficeService} 집계 — 실제 JPQL(원장×run 조인, max(id) 서브쿼리)이 H2에서 도는지까지 본다.
 * 시각은 고정 Clock + 네이티브 UPDATE로 created_at/updated_at을 옮겨 5분 창·오늘 경계를 결정적으로 만든다.
 */
@DataJpaTest
@ActiveProfiles("test")
class OfficeServiceTest {

    @Autowired PersonaRepository personas;
    @Autowired RunRepository runs;
    @Autowired GateRepository gates;
    @Autowired ToolCallAuditRepository audits;
    @Autowired UsageLedgerRepository ledger;
    @Autowired EntityManager em;

    BudgetService budgetService = mock(BudgetService.class);
    ChatAvailability chatAvailability = mock(ChatAvailability.class);
    Instant now;
    OfficeService service;

    @BeforeEach
    void setUp() {
        // 자정 근처에 돌아도 "오늘" 경계가 흔들리지 않게 한국 정오로 고정한다(행 시각은 전부 이 값 기준으로 옮긴다).
        now = LocalDate.now(OfficeService.OFFICE_ZONE).atTime(12, 0).atZone(OfficeService.OFFICE_ZONE).toInstant();
        service = new OfficeService(personas, runs, gates, audits, ledger, budgetService,
                new MeetingProperties(7L, null, false, true), chatAvailability, Clock.fixed(now, ZoneOffset.UTC));
        given(budgetService.snapshot()).willReturn(
                new BudgetService.BudgetSnapshot(new BigDecimal("100"), new BigDecimal("12.5"), false));
    }

    private Persona persona(long memberId, String slug, PersonaRole role) {
        return personas.save(Persona.of(memberId, slug, role, slug + "이름", "🤖", null));
    }

    private Run run(Persona p, long projectId, String issueKey, RunStatus target) {
        Run r = runs.save(Run.queued(RunType.TASK, issueKey, projectId, p.getId(), RunTrigger.SCHEDULER,
                "harness://default", "claude-sonnet-5"));
        switch (target) {
            case QUEUED -> { }
            case RUNNING -> r.start("pending", null);
            case WAITING_APPROVAL -> { r.start("pending", null); r.parkForApproval(); }
            case BLOCKED -> { r.start("pending", null); r.block("한도 소진"); }
            case DONE -> { r.start("pending", null); r.complete(); }
            case FAILED -> { r.start("pending", null); r.fail("실패"); }
            case CANCELLED -> r.cancel();
        }
        return runs.saveAndFlush(r);
    }

    private ToolCallAudit audit(Persona p, String tool, String summary, Instant at) {
        ToolCallAudit a = audits.saveAndFlush(ToolCallAudit.of(p.getId(), 1L, tool, summary, AuditStatus.OK));
        setTime("tool_call_audit", "created_at", a.getId(), at);
        return a;
    }

    private void cost(Run r, String usd, Instant at) {
        // 실제 적재와 똑같이 한 run의 비용을 PROJECT·PLATFORM 두 스코프로 넣는다 — 합계가 두 배가 되면 안 된다.
        UsageLedger project = ledger.saveAndFlush(UsageLedger.of(r.getId(), LedgerScope.PROJECT,
                String.valueOf(r.getProjectId()), new BigDecimal(usd), 10, 10, "m"));
        UsageLedger platform = ledger.saveAndFlush(UsageLedger.of(r.getId(), LedgerScope.PLATFORM, "platform",
                new BigDecimal(usd), 10, 10, "m"));
        setTime("usage_ledger", "created_at", project.getId(), at);
        setTime("usage_ledger", "created_at", platform.getId(), at);
    }

    private void setTime(String table, String column, long id, Instant at) {
        em.flush();
        em.createNativeQuery("update " + table + " set " + column + " = ?1 where id = ?2")
                .setParameter(1, at).setParameter(2, id).executeUpdate();
        em.clear();
    }

    private Instant todayStartKst() {
        return LocalDate.ofInstant(now, OfficeService.OFFICE_ZONE).atStartOfDay(OfficeService.OFFICE_ZONE).toInstant();
    }

    @Test
    void 빈_상태면_빈_목록과_0을_돌려준다() {
        OfficeResponse res = service.office(null);

        assertThat(res.personas()).isEmpty();
        assertThat(res.recentRuns()).isEmpty();
        assertThat(res.pendingGateCount()).isZero();
        assertThat(res.pendingGates()).isEmpty();
        assertThat(res.budget().monthlyCapUsd()).isEqualByComparingTo("100");
        assertThat(res.budget().platformMonthToDateUsd()).isEqualByComparingTo("12.5");
        assertThat(res.budget().killSwitch()).isFalse();
        assertThat(res.generatedAt()).isEqualTo(now);
    }

    /** AGP-62 — 사무실은 관리 권한을 보지 않는 표면이라 아바타만 싣고 스킬·기본 모델은 싣지 않는다. */
    @Test
    void 아바타_설정은_싣고_스킬과_기본_모델은_싣지_않는다() throws Exception {
        Persona p = persona(1L, "jiho", PersonaRole.BACKEND);
        p.edit(null, null, null, Persona.Edit.set("claude-secret-model"), Persona.Edit.set("비공개 스킬 본문"),
                Persona.Edit.set("{\"v\":1,\"hairStyle\":\"bob\"}"));
        personas.saveAndFlush(p);

        OfficeResponse res = service.office(null);

        assertThat(res.personas().get(0).avatarConfig()).isEqualTo("{\"v\":1,\"hairStyle\":\"bob\"}");
        String json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsString(res);
        assertThat(json).contains("\"avatarConfig\"").doesNotContain("skills").doesNotContain("defaultModel")
                .doesNotContain("비공개 스킬 본문").doesNotContain("claude-secret-model");
    }

    @Test
    void run이_없는_페르소나는_유휴로_나온다() {
        persona(1L, "jiho", PersonaRole.BACKEND);

        OfficeResponse res = service.office(null);

        assertThat(res.personas()).singleElement().satisfies(p -> {
            assertThat(p.slug()).isEqualTo("jiho");
            assertThat(p.role()).isEqualTo(PersonaRole.BACKEND);
            assertThat(p.active()).isTrue();
            assertThat(p.currentRun()).isNull();
            assertThat(p.lastActivity()).isNull();
            assertThat(p.todayCostUsd()).isEqualByComparingTo("0");
        });
    }

    @Test
    void 활성_run은_페르소나별_최신_1건만_붙고_종결_run은_무시된다() {
        Persona jiho = persona(1L, "jiho", PersonaRole.BACKEND);
        Persona mina = persona(2L, "mina", PersonaRole.FRONTEND);
        run(jiho, 1L, "AGP-1", RunStatus.WAITING_APPROVAL);
        Run newest = run(jiho, 1L, "AGP-2", RunStatus.RUNNING);
        run(jiho, 1L, "AGP-3", RunStatus.DONE);
        run(mina, 1L, "AGP-4", RunStatus.CANCELLED);

        OfficeResponse res = service.office(null);

        OfficeResponse.OfficePersona j = res.personas().get(0);
        assertThat(j.currentRun().id()).isEqualTo(newest.getId());
        assertThat(j.currentRun().status()).isEqualTo(RunStatus.RUNNING);
        assertThat(j.currentRun().issueKey()).isEqualTo("AGP-2");
        assertThat(j.currentRun().type()).isEqualTo(RunType.TASK);
        assertThat(j.currentRun().trigger()).isEqualTo(RunTrigger.SCHEDULER);
        assertThat(j.currentRun().attempt()).isEqualTo(1);
        assertThat(j.currentRun().model()).isEqualTo("claude-sonnet-5");
        assertThat(j.currentRun().startedAt()).isNotNull();
        assertThat(res.personas().get(1).currentRun()).isNull();
    }

    @Test
    void BLOCKED는_현재_run이면서_최근_종결_목록에도_나온다() {
        Persona jiho = persona(1L, "jiho", PersonaRole.BACKEND);
        Run blocked = run(jiho, 1L, "AGP-1", RunStatus.BLOCKED);

        OfficeResponse res = service.office(null);

        assertThat(res.personas().get(0).currentRun().status()).isEqualTo(RunStatus.BLOCKED);
        assertThat(res.recentRuns()).extracting(r -> r.id()).containsExactly(blocked.getId());
    }

    @Test
    void 최근_종결은_updatedAt_최신순_10건이고_활성_run은_빠진다() {
        Persona jiho = persona(1L, "jiho", PersonaRole.BACKEND);
        run(jiho, 1L, "AGP-0", RunStatus.RUNNING);
        Run[] done = new Run[12];
        for (int i = 0; i < 12; i++) {
            done[i] = run(jiho, 1L, "AGP-" + (i + 1), i % 2 == 0 ? RunStatus.DONE : RunStatus.FAILED);
            setTime("run", "updated_at", done[i].getId(), now.minus(Duration.ofMinutes(100 - i)));
        }

        OfficeResponse res = service.office(null);

        assertThat(res.recentRuns()).hasSize(10);
        assertThat(res.recentRuns().get(0).id()).isEqualTo(done[11].getId());
        assertThat(res.recentRuns().get(9).id()).isEqualTo(done[2].getId());
        assertThat(res.recentRuns()).allSatisfy(r -> assertThat(r.status()).isIn(RunStatus.DONE, RunStatus.FAILED));
        assertThat(res.recentRuns().get(0).type()).isEqualTo(RunType.TASK);
        assertThat(res.recentRuns().get(0).trigger()).isEqualTo(RunTrigger.SCHEDULER);
    }

    @Test
    void 말풍선은_5분_이내_최신_감사만이고_본문은_가린다() {
        Persona jiho = persona(1L, "jiho", PersonaRole.BACKEND);
        Persona mina = persona(2L, "mina", PersonaRole.FRONTEND);
        audit(jiho, "get_issue", "AGP-1", now.minus(Duration.ofMinutes(3)));
        audit(jiho, "add_comment", "AGP-1: 비밀스러운 코멘트 본문", now.minus(Duration.ofMinutes(1)));
        audit(mina, "create_page", "spaceId=1 title=설계", now.minus(Duration.ofMinutes(6)));

        OfficeResponse res = service.office(null);

        assertThat(res.personas().get(0).lastActivity()).satisfies(a -> {
            assertThat(a.tool()).isEqualTo("add_comment");
            assertThat(a.summary()).isEqualTo("AGP-1 " + AuditSummaryRedactor.BODY_REDACTED);
            assertThat(a.status()).isEqualTo(AuditStatus.OK);
        });
        assertThat(res.personas().get(1).lastActivity()).isNull();
    }

    @Test
    void 오늘_비용은_PLATFORM_스코프만_페르소나별로_합산하고_어제는_뺀다() {
        Persona jiho = persona(1L, "jiho", PersonaRole.BACKEND);
        Persona mina = persona(2L, "mina", PersonaRole.FRONTEND);
        Run r1 = run(jiho, 1L, "AGP-1", RunStatus.DONE);
        Run r2 = run(jiho, 2L, "OPS-1", RunStatus.DONE);
        Run r3 = run(mina, 1L, "AGP-2", RunStatus.DONE);
        cost(r1, "1.2500", todayStartKst().plusSeconds(60));
        cost(r2, "0.5000", now);
        cost(r1, "9.0000", todayStartKst().minusSeconds(60));
        cost(r3, "2.0000", now);

        OfficeResponse all = service.office(null);
        assertThat(all.personas().get(0).todayCostUsd()).isEqualByComparingTo("1.75");
        assertThat(all.personas().get(1).todayCostUsd()).isEqualByComparingTo("2");

        OfficeResponse agp = service.office(1L);
        assertThat(agp.personas().get(0).todayCostUsd()).isEqualByComparingTo("1.25");
    }

    @Test
    void projectId를_주면_run_축이_그_프로젝트로_좁혀진다() {
        Persona jiho = persona(1L, "jiho", PersonaRole.BACKEND);
        run(jiho, 2L, "OPS-1", RunStatus.RUNNING);
        run(jiho, 2L, "OPS-2", RunStatus.DONE);
        Run agpDone = run(jiho, 1L, "AGP-1", RunStatus.DONE);
        Run opsWaiting = run(jiho, 2L, "OPS-3", RunStatus.WAITING_APPROVAL);
        gates.saveAndFlush(Gate.request(opsWaiting.getId(), GateKind.MERGE, "머지 승인"));

        OfficeResponse res = service.office(1L);

        assertThat(res.personas().get(0).currentRun()).isNull();
        assertThat(res.recentRuns()).extracting(r -> r.id()).containsExactly(agpDone.getId());
        assertThat(res.pendingGateCount()).isZero();
        assertThat(res.pendingGates()).isEmpty();
    }

    /** P3f(D-P3f-3) — 프로젝트 필터 시 페르소나는 그 프로젝트 소속 + 공용. 다른 프로젝트 소속은 이 프로젝트 run이 있을 때만. */
    @Test
    void projectId를_주면_페르소나는_그_프로젝트_소속과_공용만_나온다() {
        Persona shared = persona(1L, "shared", PersonaRole.BACKEND);
        Persona own = personas.save(Persona.of(2L, "own", PersonaRole.BACKEND, "own이름", "🤖", null, 1L));
        personas.save(Persona.of(3L, "other", PersonaRole.BACKEND, "other이름", "🤖", null, 2L));
        Persona visiting = personas.save(Persona.of(4L, "visiting", PersonaRole.BACKEND, "visiting이름", "🤖", null, 2L));
        run(visiting, 1L, "AGP-5", RunStatus.RUNNING);

        assertThat(service.office(1L).personas()).extracting(p -> p.slug())
                .containsExactly(shared.getSlug(), own.getSlug(), visiting.getSlug());
        assertThat(service.office(null).personas()).hasSize(4);
    }

    @Test
    void 미결_게이트는_개수_전체와_최신_5건_요약을_준다() {
        Persona jiho = persona(1L, "jiho", PersonaRole.BACKEND);
        Gate last = null;
        for (int i = 0; i < 7; i++) {
            Run r = run(jiho, 1L, "AGP-" + i, RunStatus.WAITING_APPROVAL);
            last = gates.saveAndFlush(Gate.request(r.getId(), GateKind.PLAN, "가".repeat(300)));
            setTime("gate", "requested_at", last.getId(), now.minus(Duration.ofMinutes(10 - i)));
        }
        Run decidedRun = run(jiho, 1L, "AGP-99", RunStatus.WAITING_APPROVAL);
        Gate decided = Gate.request(decidedRun.getId(), GateKind.MERGE, "결정됨");
        decided.approve(1L);
        gates.saveAndFlush(decided);

        OfficeResponse res = service.office(null);

        assertThat(res.pendingGateCount()).isEqualTo(7);
        assertThat(res.pendingGates()).hasSize(5);
        OfficeResponse.PendingGate top = res.pendingGates().get(0);
        assertThat(top.id()).isEqualTo(last.getId());
        assertThat(top.issueKey()).isEqualTo("AGP-6");
        assertThat(top.personaId()).isEqualTo(jiho.getId());
        assertThat(top.kind()).isEqualTo(GateKind.PLAN);
        assertThat(top.requestSummary()).hasSize(OfficeService.GATE_SUMMARY_MAX + 1).endsWith("…");
    }

    @Test
    void 게이트_요약은_200자_경계에_걸린_이모지를_반쪽으로_자르지_않는다() {
        // 🔧는 서로게이트 쌍(2 char) — 199번째 char부터 시작하므로 200자에서 자르면 상위 서로게이트만 남는다.
        String request = "a".repeat(OfficeService.GATE_SUMMARY_MAX - 1) + "🔧" + "뒤쪽";

        String summary = OfficeService.abbreviate(request);

        assertThat(summary).isEqualTo("a".repeat(OfficeService.GATE_SUMMARY_MAX - 1) + "…");
        assertThat(summary.chars().anyMatch(c -> Character.isSurrogate((char) c))).isFalse();
    }

    @Test
    void 게이트_요약은_이모지가_경계_안에_온전히_들어가면_그대로_둔다() {
        String request = "a".repeat(OfficeService.GATE_SUMMARY_MAX - 2) + "🔧" + "뒤쪽";

        assertThat(OfficeService.abbreviate(request))
                .isEqualTo("a".repeat(OfficeService.GATE_SUMMARY_MAX - 2) + "🔧…");
    }

    @Test
    void 개인_활동은_최근_run_20건과_오늘_감사_50건과_오늘_비용을_준다() {
        Persona jiho = persona(1L, "jiho", PersonaRole.BACKEND);
        Persona mina = persona(2L, "mina", PersonaRole.FRONTEND);
        Run first = null;
        for (int i = 0; i < 22; i++) {
            Run r = run(jiho, 1L, "AGP-" + i, RunStatus.DONE);
            if (i == 0) first = r;
        }
        run(mina, 1L, "AGP-M", RunStatus.RUNNING);
        for (int i = 0; i < 52; i++) {
            audit(jiho, "report_progress", "run=1 진행 메시지 " + i, now.minusSeconds(60 + i));
        }
        audit(jiho, "get_issue", "AGP-OLD", todayStartKst().minusSeconds(1));
        cost(first, "0.3000", now);

        PersonaActivityResponse res = service.activity(jiho.getId());

        assertThat(res.personaId()).isEqualTo(jiho.getId());
        assertThat(res.runs()).hasSize(20).allSatisfy(r -> assertThat(r.personaId()).isEqualTo(jiho.getId()));
        assertThat(res.runs()).extracting(r -> r.id()).doesNotContain(first.getId());
        assertThat(res.todayAudits()).hasSize(50);
        assertThat(res.todayAudits()).noneMatch(a -> "AGP-OLD".equals(a.summary()));
        assertThat(res.todayAudits().get(0).summary()).isEqualTo("run=1 " + AuditSummaryRedactor.BODY_REDACTED);
        assertThat(res.todayCostUsd()).isEqualByComparingTo("0.3");
    }

    @Test
    void 활동_기록이_없는_페르소나는_빈_목록과_0이다() {
        Persona jiho = persona(1L, "jiho", PersonaRole.BACKEND);

        PersonaActivityResponse res = service.activity(jiho.getId());

        assertThat(res.runs()).isEmpty();
        assertThat(res.todayAudits()).isEmpty();
        assertThat(res.todayCostUsd()).isEqualByComparingTo("0");
    }

    @Test
    void 없는_페르소나_활동은_404() {
        assertThatThrownBy(() -> service.activity(9999L)).isInstanceOf(NotFoundException.class);
    }

    // ---- P3b: 게시판(boardPosts) ----

    private Run meetingRun(Persona p, RunType type, long projectId, String issueKey, RunStatus target, Long pageId,
                           Instant endedAt) {
        Run r = runs.save(Run.queuedMeeting(type, issueKey, projectId, java.util.List.of(p.getId()), RunTrigger.USER,
                "harness://default", null, "안건"));
        r.start("pending", null);
        if (pageId != null) {
            r.recordOutputPage(pageId);
        }
        if (target == RunStatus.DONE) {
            r.complete();
        } else {
            r.fail("실패");
        }
        r = runs.saveAndFlush(r);
        setTime("run", "ended_at", r.getId(), endedAt);
        return r;
    }

    @Test
    void 게시판은_회의록이_보고된_완료_회의_run_최신_5건이다() {
        Persona p = persona(1L, "seoyeon", PersonaRole.PLANNER);
        Run oldest = meetingRun(p, RunType.MEETING, 1L, "PROJECT-1", RunStatus.DONE, 500L, now.minusSeconds(600));
        Run retro = meetingRun(p, RunType.RETRO, 1L, "PROJECT-1", RunStatus.DONE, 501L, now.minusSeconds(500));
        Run escalation = meetingRun(p, RunType.ESCALATION, 1L, "AGP-9", RunStatus.DONE, 502L, now.minusSeconds(400));
        Run m3 = meetingRun(p, RunType.MEETING, 2L, "WEB-1", RunStatus.DONE, 503L, now.minusSeconds(300));
        Run m4 = meetingRun(p, RunType.MEETING, 1L, "AGP-3", RunStatus.DONE, 504L, now.minusSeconds(200));
        Run newest = meetingRun(p, RunType.RETRO, 1L, "PROJECT-1", RunStatus.DONE, 505L, now.minusSeconds(100));
        // 제외 대상: 회의록 없는 완료, 실패한 회의, 페이지를 보고한 TASK.
        meetingRun(p, RunType.MEETING, 1L, "PROJECT-1", RunStatus.DONE, null, now.minusSeconds(50));
        meetingRun(p, RunType.MEETING, 1L, "PROJECT-1", RunStatus.FAILED, 506L, now.minusSeconds(40));
        Run task = runs.save(Run.queued(RunType.TASK, "AGP-1", 1L, p.getId(), RunTrigger.USER, "harness://default", null));
        task.start("pending", null);
        task.recordOutputPage(507L);
        task.complete();
        runs.saveAndFlush(task);

        OfficeResponse res = service.office(null);

        assertThat(res.boardPosts()).extracting(OfficeResponse.BoardPost::runId)
                .containsExactly(newest.getId(), m4.getId(), m3.getId(), escalation.getId(), retro.getId());
        OfficeResponse.BoardPost first = res.boardPosts().get(0);
        assertThat(first.type()).isEqualTo(RunType.RETRO);
        assertThat(first.issueKey()).isEqualTo("PROJECT-1");
        assertThat(first.projectId()).isEqualTo(1L);
        assertThat(first.pageId()).isEqualTo(505L);
        assertThat(first.spaceId()).isEqualTo(7L);
        assertThat(first.endedAt()).isEqualTo(now.minusSeconds(100));
        assertThat(res.boardPosts()).extracting(OfficeResponse.BoardPost::runId).doesNotContain(oldest.getId());
    }

    @Test
    void 게시판도_projectId로_좁혀진다() {
        Persona p = persona(1L, "seoyeon", PersonaRole.PLANNER);
        Run mine = meetingRun(p, RunType.MEETING, 1L, "PROJECT-1", RunStatus.DONE, 500L, now.minusSeconds(100));
        meetingRun(p, RunType.MEETING, 2L, "PROJECT-2", RunStatus.DONE, 501L, now.minusSeconds(50));

        assertThat(service.office(1L).boardPosts()).extracting(OfficeResponse.BoardPost::runId)
                .containsExactly(mine.getId());
        assertThat(service.office(null).boardPosts()).hasSize(2);
    }

    @Test
    void 회의록_스페이스가_미설정이면_게시물_spaceId는_null이다() {
        Persona p = persona(1L, "seoyeon", PersonaRole.PLANNER);
        meetingRun(p, RunType.MEETING, 1L, "PROJECT-1", RunStatus.DONE, 500L, now.minusSeconds(100));
        OfficeService noSpace = new OfficeService(personas, runs, gates, audits, ledger, budgetService,
                new MeetingProperties(null, null, false, true), chatAvailability, Clock.fixed(now, ZoneOffset.UTC));

        assertThat(noSpace.office(null).boardPosts()).singleElement()
                .satisfies(post -> assertThat(post.spaceId()).isNull());
    }

    // ---- P3g: 기능 플래그 ----

    @Test
    void features_chat은_projectId_문맥으로_판정한_값이다() {
        given(chatAvailability.available(3L)).willReturn(true);
        given(chatAvailability.available(null)).willReturn(false);

        assertThat(service.office(3L).features().chat()).isTrue();
        assertThat(service.office(null).features().chat()).isFalse();
    }

    // ---- P3e: 회의실(activeMeeting) ----

    private Run liveMeeting(RunType type, long projectId, String issueKey, RunStatus target, Persona... attendees) {
        Run r = runs.save(Run.queuedMeeting(type, issueKey, projectId,
                java.util.Arrays.stream(attendees).map(Persona::getId).toList(), RunTrigger.USER,
                "harness://default", null, "안건"));
        switch (target) {
            case QUEUED -> { }
            case RUNNING -> r.start("pending", null);
            case WAITING_APPROVAL -> { r.start("pending", null); r.parkForApproval(); }
            case BLOCKED -> { r.start("pending", null); r.block("사고형 실패"); }
            default -> throw new IllegalArgumentException(target.name());
        }
        return runs.saveAndFlush(r);
    }

    @Test
    void 진행_중인_회의가_있으면_activeMeeting에_진행자와_참석자_순서를_싣는다() {
        Persona planner = persona(1L, "seoyeon", PersonaRole.PLANNER);
        Persona backend = persona(2L, "jiho", PersonaRole.BACKEND);
        Persona reviewer = persona(3L, "yuna", PersonaRole.REVIEWER);
        // 저장 순서가 id 순서와 다르다 — 정렬하지 않고 저장 순서 그대로 나와야 한다.
        Run meeting = liveMeeting(RunType.MEETING, 1L, "AGP-7", RunStatus.RUNNING, backend, reviewer, planner);

        OfficeResponse.ActiveMeeting m = service.office(null).activeMeeting();

        assertThat(m).isNotNull();
        assertThat(m.runId()).isEqualTo(meeting.getId());
        assertThat(m.type()).isEqualTo(RunType.MEETING);
        assertThat(m.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(m.issueKey()).isEqualTo("AGP-7");
        assertThat(m.projectId()).isEqualTo(1L);
        assertThat(m.hostPersonaId()).isEqualTo(backend.getId());
        assertThat(m.attendeePersonaIds()).containsExactly(backend.getId(), reviewer.getId(), planner.getId());
        assertThat(m.startedAt()).isNotNull();
    }

    @Test
    void 매니저_순찰도_1인_회의로_잡힌다() {
        Persona manager = persona(1L, "boss", PersonaRole.MANAGER);
        liveMeeting(RunType.MANAGER, 1L, "PROJECT-1", RunStatus.RUNNING, manager);

        OfficeResponse.ActiveMeeting m = service.office(null).activeMeeting();

        assertThat(m.type()).isEqualTo(RunType.MANAGER);
        assertThat(m.issueKey()).isEqualTo("PROJECT-1");
        assertThat(m.hostPersonaId()).isEqualTo(manager.getId());
        assertThat(m.attendeePersonaIds()).containsExactly(manager.getId());
    }

    @Test
    void 대기_승인대기_차단_회의와_TASK_실행은_회의실에_없다() {
        Persona p = persona(1L, "seoyeon", PersonaRole.PLANNER);
        liveMeeting(RunType.MEETING, 1L, "PROJECT-1", RunStatus.QUEUED, p);
        liveMeeting(RunType.RETRO, 2L, "PROJECT-2", RunStatus.WAITING_APPROVAL, p);
        liveMeeting(RunType.ESCALATION, 3L, "OPS-1", RunStatus.BLOCKED, p);
        run(p, 1L, "AGP-1", RunStatus.RUNNING);

        assertThat(service.office(null).activeMeeting()).isNull();
    }

    @Test
    void 회의실은_projectId로_좁혀지고_여럿이면_최신_1건이다() {
        Persona p = persona(1L, "seoyeon", PersonaRole.PLANNER);
        Persona q = persona(2L, "jiho", PersonaRole.BACKEND);
        Run agp = liveMeeting(RunType.MEETING, 1L, "PROJECT-1", RunStatus.RUNNING, p);
        Run ops = liveMeeting(RunType.RETRO, 2L, "PROJECT-2", RunStatus.RUNNING, q);

        assertThat(service.office(null).activeMeeting().runId()).isEqualTo(ops.getId());
        assertThat(service.office(1L).activeMeeting().runId()).isEqualTo(agp.getId());
        assertThat(service.office(3L).activeMeeting()).isNull();
    }
}
