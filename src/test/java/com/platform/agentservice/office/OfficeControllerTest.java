package com.platform.agentservice.office;

import com.platform.agentservice.TestAuth;
import com.platform.agentservice.audit.AuditOrigin;
import com.platform.agentservice.audit.AuditStatus;
import com.platform.agentservice.budget.dto.BudgetStatusResponse;
import com.platform.agentservice.office.dto.AuditEntry;
import com.platform.agentservice.office.dto.OfficeResponse;
import com.platform.agentservice.office.dto.PersonaActivityResponse;
import com.platform.agentservice.office.dto.PersonaPresence;
import com.platform.agentservice.persona.PersonaRole;
import com.platform.agentservice.run.GateKind;
import com.platform.agentservice.run.RunStatus;
import com.platform.agentservice.run.RunTrigger;
import com.platform.agentservice.run.RunType;
import com.platform.agentservice.run.dto.RunSummaryResponse;
import com.platform.common.error.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@link OfficeController} — 인가(인증 사용자 누구나)·JSON 필드명(프론트 계약)·404만 본다. 집계는 {@link OfficeServiceTest}. */
@SpringBootTest
@ActiveProfiles("test")
class OfficeControllerTest {

    @Autowired WebApplicationContext context;
    @MockitoBean OfficeService officeService;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private static final Instant T = Instant.parse("2026-09-26T03:00:00Z");

    @Test
    void 인증_없으면_401() throws Exception {
        mvc.perform(get("/api/agent/office")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/agent/personas/1/activity")).andExpect(status().isUnauthorized());
    }

    @Test
    void 일반_사용자가_사무실을_조회하면_계약_필드가_내려온다() throws Exception {
        OfficeResponse res = new OfficeResponse(
                List.of(new OfficeResponse.OfficePersona(3L, "jiho", "지호", "🔧", PersonaRole.BACKEND, true,
                        new OfficeResponse.CurrentRun(11L, RunStatus.RUNNING, "AGP-1", RunType.TASK, RunTrigger.USER, 2, "claude-sonnet-5", T),
                        new AuditEntry(99L, "get_issue", AuditStatus.OK, "AGP-1", T),
                        new BigDecimal("1.2500"))),
                List.of(new RunSummaryResponse(10L, "AGP-0", RunStatus.DONE, 3L, 1, null, T, T,
                        RunType.REVIEW, RunTrigger.USER, 9L)),
                1L,
                List.of(new OfficeResponse.PendingGate(5L, 12L, "AGP-2", 3L, GateKind.MERGE, "머지 승인", T)),
                new BudgetStatusResponse(new BigDecimal("100"), new BigDecimal("12.5"), false),
                T,
                List.of(new OfficeResponse.BoardPost(21L, RunType.MEETING, "AGP-3", 7L, 501L, 9L, T)),
                new OfficeResponse.ActiveMeeting(30L, RunType.RETRO, RunStatus.RUNNING, "PROJECT-7", 7L, 4L,
                        List.of(4L, 3L), T),
                new OfficeResponse.Features(true));
        given(officeService.office(7L)).willReturn(res);

        mvc.perform(get("/api/agent/office").param("projectId", "7").with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personas[0].id").value(3))
                .andExpect(jsonPath("$.personas[0].slug").value("jiho"))
                .andExpect(jsonPath("$.personas[0].role").value("BACKEND"))
                .andExpect(jsonPath("$.personas[0].active").value(true))
                .andExpect(jsonPath("$.personas[0].currentRun.status").value("RUNNING"))
                .andExpect(jsonPath("$.personas[0].currentRun.issueKey").value("AGP-1"))
                .andExpect(jsonPath("$.personas[0].currentRun.trigger").value("USER"))
                .andExpect(jsonPath("$.personas[0].currentRun.startedAt").value("2026-09-26T03:00:00Z"))
                .andExpect(jsonPath("$.personas[0].lastActivity.tool").value("get_issue"))
                .andExpect(jsonPath("$.personas[0].lastActivity.createdAt").value("2026-09-26T03:00:00Z"))
                .andExpect(jsonPath("$.personas[0].todayCostUsd").value(1.25))
                .andExpect(jsonPath("$.recentRuns[0].type").value("REVIEW"))
                .andExpect(jsonPath("$.recentRuns[0].parentRunId").value(9))
                .andExpect(jsonPath("$.pendingGateCount").value(1))
                .andExpect(jsonPath("$.pendingGates[0].requestSummary").value("머지 승인"))
                .andExpect(jsonPath("$.budget.monthlyCapUsd").value(100))
                .andExpect(jsonPath("$.budget.killSwitch").value(false))
                .andExpect(jsonPath("$.generatedAt").value("2026-09-26T03:00:00Z"))
                .andExpect(jsonPath("$.boardPosts[0].runId").value(21))
                .andExpect(jsonPath("$.boardPosts[0].type").value("MEETING"))
                .andExpect(jsonPath("$.boardPosts[0].issueKey").value("AGP-3"))
                .andExpect(jsonPath("$.boardPosts[0].projectId").value(7))
                .andExpect(jsonPath("$.boardPosts[0].pageId").value(501))
                .andExpect(jsonPath("$.boardPosts[0].spaceId").value(9))
                .andExpect(jsonPath("$.boardPosts[0].endedAt").value("2026-09-26T03:00:00Z"))
                .andExpect(jsonPath("$.activeMeeting.runId").value(30))
                .andExpect(jsonPath("$.activeMeeting.type").value("RETRO"))
                .andExpect(jsonPath("$.activeMeeting.status").value("RUNNING"))
                .andExpect(jsonPath("$.activeMeeting.issueKey").value("PROJECT-7"))
                .andExpect(jsonPath("$.activeMeeting.projectId").value(7))
                .andExpect(jsonPath("$.activeMeeting.hostPersonaId").value(4))
                .andExpect(jsonPath("$.activeMeeting.attendeePersonaIds[0]").value(4))
                .andExpect(jsonPath("$.activeMeeting.attendeePersonaIds[1]").value(3))
                .andExpect(jsonPath("$.activeMeeting.startedAt").value("2026-09-26T03:00:00Z"))
                .andExpect(jsonPath("$.features.chat").value(true));
    }

    /** AGP-63 — lastActivity·todayAudits의 origin·runId, personas[].presence 직렬화(없으면 null). */
    @Test
    void 실행_출처와_재실이_직렬화된다() throws Exception {
        given(officeService.office(null)).willReturn(new OfficeResponse(List.of(
                new OfficeResponse.OfficePersona(3L, "jiho", "지호", "🔧", PersonaRole.BACKEND, true, null,
                        new AuditEntry(99L, "get_issue", AuditStatus.OK, "AGP-1", T, AuditOrigin.EXTERNAL, null),
                        BigDecimal.ZERO, null, PersonaPresence.EXTERNAL),
                new OfficeResponse.OfficePersona(4L, "mina", "미나", "🎨", PersonaRole.FRONTEND, true, null,
                        new AuditEntry(98L, "get_issue", AuditStatus.OK, "AGP-2", T, AuditOrigin.WORKER, 11L),
                        BigDecimal.ZERO, null, null),
                new OfficeResponse.OfficePersona(5L, "old", "옛날", "🤖", PersonaRole.OPS, true, null,
                        new AuditEntry(97L, "get_issue", AuditStatus.OK, "AGP-3", T), BigDecimal.ZERO)),
                List.of(), 0, List.of(), new BudgetStatusResponse(BigDecimal.TEN, BigDecimal.ZERO, false), T, List.of(),
                null, new OfficeResponse.Features(false)));
        given(officeService.activity(4L)).willReturn(new PersonaActivityResponse(4L, List.of(),
                List.of(new AuditEntry(1L, "report_progress", AuditStatus.OK, "run=11 (본문 생략)", T,
                        AuditOrigin.WORKER, 11L)), BigDecimal.ZERO));

        mvc.perform(get("/api/agent/office").with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personas[0].presence").value("EXTERNAL"))
                .andExpect(jsonPath("$.personas[0].lastActivity.origin").value("EXTERNAL"))
                .andExpect(jsonPath("$.personas[0].lastActivity.runId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.personas[1].presence").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.personas[1].lastActivity.origin").value("WORKER"))
                .andExpect(jsonPath("$.personas[1].lastActivity.runId").value(11))
                .andExpect(jsonPath("$.personas[2].lastActivity.origin").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.personas[2].lastActivity.runId").value(org.hamcrest.Matchers.nullValue()));

        mvc.perform(get("/api/agent/personas/4/activity").with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.todayAudits[0].origin").value("WORKER"))
                .andExpect(jsonPath("$.todayAudits[0].runId").value(11));
    }

    @Test
    void projectId_없이도_조회된다() throws Exception {
        given(officeService.office(null)).willReturn(new OfficeResponse(List.of(), List.of(), 0, List.of(),
                new BudgetStatusResponse(BigDecimal.TEN, BigDecimal.ZERO, false), T, List.of(), null,
                new OfficeResponse.Features(false)));

        mvc.perform(get("/api/agent/office").with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personas").isEmpty())
                .andExpect(jsonPath("$.activeMeeting").value(org.hamcrest.Matchers.nullValue()));

        verify(officeService).office(null);
    }

    @Test
    void 개인_활동을_조회한다() throws Exception {
        given(officeService.activity(3L)).willReturn(new PersonaActivityResponse(3L, List.of(),
                List.of(new AuditEntry(1L, "add_comment", AuditStatus.ERROR, "AGP-1 (본문 생략)", T)), BigDecimal.ZERO));

        mvc.perform(get("/api/agent/personas/3/activity").with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personaId").value(3))
                .andExpect(jsonPath("$.runs").isEmpty())
                .andExpect(jsonPath("$.todayAudits[0].status").value("ERROR"))
                .andExpect(jsonPath("$.todayCostUsd").value(0));
    }

    @Test
    void 없는_페르소나는_404와_error_본문() throws Exception {
        given(officeService.activity(404L)).willThrow(new NotFoundException("페르소나를 찾을 수 없습니다: 404"));

        mvc.perform(get("/api/agent/personas/404/activity").with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("페르소나를 찾을 수 없습니다: 404"));
    }
}
