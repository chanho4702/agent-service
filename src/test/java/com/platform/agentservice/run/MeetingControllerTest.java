package com.platform.agentservice.run;

import com.platform.agentservice.TestAuth;
import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRole;
import com.platform.common.error.ConflictException;
import com.platform.common.error.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@link MeetingController} — 인가(ADMIN)·요청 검증·응답 shape·{"error"} 매핑만 본다. 소집 규칙은 {@link MeetingServiceTest}. */
@SpringBootTest
@ActiveProfiles("test")
class MeetingControllerTest {

    @Autowired WebApplicationContext context;
    @MockitoBean MeetingService meetingService;
    @MockitoBean RunService runService;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private static Persona persona(long id, String slug, PersonaRole role, String emoji) {
        Persona p = Persona.of(100L + id, slug, role, slug + "-name", emoji, null);
        ReflectionTestUtils.setField(p, "id", id);
        return p;
    }

    private static MeetingService.MeetingCreated created(long runId) {
        Run run = Run.queuedMeeting(RunType.MEETING, "AGP-3", 1L, List.of(1L, 4L), RunTrigger.USER, "harness://default",
                "claude-sonnet-5", "로그인 개편");
        ReflectionTestUtils.setField(run, "id", runId);
        return new MeetingService.MeetingCreated(run, List.of(persona(1L, "seoyeon", PersonaRole.PLANNER, "🗂"),
                persona(4L, "jiho", PersonaRole.BACKEND, "🔧")));
    }

    @Test
    void admin_convenes_meeting_201_with_run_summary_and_attendees_and_submits_execution() throws Exception {
        given(meetingService.createMeeting(RunType.MEETING, 1L, "AGP-3", "로그인 개편", List.of("seoyeon", "jiho")))
                .willReturn(created(90L));

        mvc.perform(post("/api/agent/meetings").with(authentication(TestAuth.admin(1L, "Admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"MEETING\",\"projectId\":1,\"agendaIssueKey\":\"AGP-3\","
                                + "\"agenda\":\"로그인 개편\",\"personaSlugs\":[\"seoyeon\",\"jiho\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.run.id").value(90))
                .andExpect(jsonPath("$.run.type").value("MEETING"))
                .andExpect(jsonPath("$.run.status").value("QUEUED"))
                .andExpect(jsonPath("$.run.trigger").value("USER"))
                .andExpect(jsonPath("$.run.issueKey").value("AGP-3"))
                .andExpect(jsonPath("$.run.personaId").value(1))
                .andExpect(jsonPath("$.run.parentRunId").value(nullValue()))
                .andExpect(jsonPath("$.attendees[0].personaId").value(1))
                .andExpect(jsonPath("$.attendees[0].slug").value("seoyeon"))
                .andExpect(jsonPath("$.attendees[0].name").value("seoyeon-name"))
                .andExpect(jsonPath("$.attendees[0].role").value("PLANNER"))
                .andExpect(jsonPath("$.attendees[0].emoji").value("🗂"))
                .andExpect(jsonPath("$.attendees[1].slug").value("jiho"));

        verify(runService).execute(90L);
    }

    @Test
    void optional_fields_are_passed_as_null() throws Exception {
        given(meetingService.createMeeting(RunType.RETRO, 1L, null, null, null)).willReturn(created(91L));

        mvc.perform(post("/api/agent/meetings").with(authentication(TestAuth.admin(1L, "Admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"RETRO\",\"projectId\":1}"))
                .andExpect(status().isCreated());

        verify(meetingService).createMeeting(RunType.RETRO, 1L, null, null, null);
    }

    @Test
    void submission_rejection_still_returns_201() throws Exception {
        given(meetingService.createMeeting(RunType.RETRO, 1L, null, null, null)).willReturn(created(92L));
        willThrow(new TaskRejectedException("pool full")).given(runService).execute(92L);

        mvc.perform(post("/api/agent/meetings").with(authentication(TestAuth.admin(1L, "Admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"RETRO\",\"projectId\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.run.status").value("QUEUED"));
    }

    @Test
    void non_admin_is_forbidden() throws Exception {
        mvc.perform(post("/api/agent/meetings").with(authentication(TestAuth.user(2L, "Bob")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"RETRO\",\"projectId\":1}"))
                .andExpect(status().isForbidden());

        verify(meetingService, never()).createMeeting(any(), anyLong(), any(), any(), any());
    }

    @Test
    void missing_type_or_project_or_unknown_type_is_400_with_error_body() throws Exception {
        mvc.perform(post("/api/agent/meetings").with(authentication(TestAuth.admin(1L, "Admin")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"projectId\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
        mvc.perform(post("/api/agent/meetings").with(authentication(TestAuth.admin(1L, "Admin")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"MEETING\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
        mvc.perform(post("/api/agent/meetings").with(authentication(TestAuth.admin(1L, "Admin")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"STANDUP\",\"projectId\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        verify(meetingService, never()).createMeeting(any(), anyLong(), any(), any(), any());
    }

    @Test
    void too_long_agenda_issue_key_is_400() throws Exception {
        mvc.perform(post("/api/agent/meetings").with(authentication(TestAuth.admin(1L, "Admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"MEETING\",\"projectId\":1,\"agendaIssueKey\":\"" + "A".repeat(41) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void service_errors_map_to_400_404_409_with_error_body() throws Exception {
        given(meetingService.createMeeting(RunType.TASK, 1L, null, "x", null))
                .willThrow(new IllegalArgumentException("회의 종류는 MEETING·RETRO·ESCALATION 중 하나여야 합니다: TASK"));
        given(meetingService.createMeeting(RunType.MEETING, 1L, null, "x", List.of("ghost")))
                .willThrow(new NotFoundException("페르소나를 찾을 수 없습니다: ghost"));
        given(meetingService.createMeeting(RunType.RETRO, 1L, null, null, null))
                .willThrow(new ConflictException("이 프로젝트에 이미 진행 중인 회의 run이 있습니다: projectId=1"));

        mvc.perform(post("/api/agent/meetings").with(authentication(TestAuth.admin(1L, "Admin")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"TASK\",\"projectId\":1,\"agenda\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("회의 종류는 MEETING·RETRO·ESCALATION 중 하나여야 합니다: TASK"));
        mvc.perform(post("/api/agent/meetings").with(authentication(TestAuth.admin(1L, "Admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"MEETING\",\"projectId\":1,\"agenda\":\"x\",\"personaSlugs\":[\"ghost\"]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("페르소나를 찾을 수 없습니다: ghost"));
        mvc.perform(post("/api/agent/meetings").with(authentication(TestAuth.admin(1L, "Admin")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"RETRO\",\"projectId\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("이 프로젝트에 이미 진행 중인 회의 run이 있습니다: projectId=1"));

        verify(runService, never()).execute(anyLong());
    }
}
