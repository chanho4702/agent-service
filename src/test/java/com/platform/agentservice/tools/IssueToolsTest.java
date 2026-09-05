package com.platform.agentservice.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.platform.agentservice.audit.AuditService;
import com.platform.agentservice.audit.AuditStatus;
import com.platform.agentservice.client.AlmClient;
import com.platform.agentservice.client.TokenService;
import com.platform.agentservice.client.dto.CommentResponse;
import com.platform.agentservice.client.dto.IssueCreateRequest;
import com.platform.agentservice.client.dto.IssueResponse;
import com.platform.agentservice.client.dto.IssueUpdateRequest;
import com.platform.agentservice.pat.PatPrincipal;
import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRepository;
import com.platform.agentservice.persona.PersonaRole;
import com.platform.common.error.ForbiddenException;
import com.platform.common.error.ServiceUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IssueToolsTest {

    private static final long OWNER_MEMBER_ID = 100L;
    private static final long PERSONA_ID = 5L;
    private static final long PERSONA_MEMBER_ID = 42L;
    private static final String BEARER = "Bearer persona-token";

    @Mock AlmClient almClient;
    @Mock TokenService tokenService;
    @Mock PersonaRepository personaRepository;
    @Mock AuditService auditService;

    private IssueTools issueTools;

    @BeforeEach
    void setUp() {
        Audited audited = new Audited(auditService);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        issueTools = new IssueTools(almClient, tokenService, personaRepository, audited, objectMapper);

        PatPrincipal principal = new PatPrincipal(OWNER_MEMBER_ID, PERSONA_ID, PERSONA_MEMBER_ID);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority("ROLE_AGENT"))));

        // lenient: 일부 테스트(오류 경로)에서는 실제로 토큰이 필요하지 않을 수 있다.
        org.mockito.Mockito.lenient().when(tokenService.bearerFor(PERSONA_MEMBER_ID)).thenReturn(BEARER);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---- ToolActor ----

    @Test
    void toolActor_throws_forbidden_without_pat_principal() {
        SecurityContextHolder.clearContext();
        org.junit.jupiter.api.Assertions.assertThrows(ForbiddenException.class, ToolActor::current);
    }

    // ---- claim_issue: GET->PUT, 409 재시도 1회 ----

    @Test
    void claim_issue_fetches_then_full_replaces_with_persona_as_assignee_and_inprogress_status() {
        IssueResponse fetched = issue(1L, "PROJ-1", "todo", null, 2);
        when(almClient.getByKey("PROJ-1", BEARER)).thenReturn(fetched);
        IssueUpdateRequest expectedRequest = new IssueUpdateRequest(
                fetched.title(), fetched.description(), fetched.type(), "inprogress", fetched.priority(),
                PERSONA_MEMBER_ID, null, 2, null);
        IssueResponse updated = issue(1L, "PROJ-1", "inprogress", PERSONA_MEMBER_ID, 3);
        when(almClient.update(1L, expectedRequest, BEARER)).thenReturn(updated);

        String result = issueTools.claimIssue("PROJ-1");

        assertThat(result).contains("PROJ-1").contains("inprogress");
        verify(almClient, times(1)).update(eq(1L), eq(expectedRequest), eq(BEARER));
        verify(auditService).record(eq(PERSONA_ID), eq(OWNER_MEMBER_ID), eq("claim_issue"), org.mockito.ArgumentMatchers.anyString(), eq(AuditStatus.OK));
    }

    @Test
    void claim_issue_retries_once_after_409_conflict() {
        IssueResponse fetchedV2 = issue(1L, "PROJ-1", "todo", null, 2);
        IssueResponse fetchedV3 = issue(1L, "PROJ-1", "todo", null, 3);
        // 첫 GET은 최초 조회, 두 번째 GET은 409 후 재조회
        when(almClient.getByKey("PROJ-1", BEARER)).thenReturn(fetchedV2, fetchedV3);

        IssueUpdateRequest firstAttempt = new IssueUpdateRequest(
                fetchedV2.title(), fetchedV2.description(), fetchedV2.type(), "inprogress", fetchedV2.priority(),
                PERSONA_MEMBER_ID, null, 2, null);
        IssueUpdateRequest secondAttempt = new IssueUpdateRequest(
                fetchedV3.title(), fetchedV3.description(), fetchedV3.type(), "inprogress", fetchedV3.priority(),
                PERSONA_MEMBER_ID, null, 3, null);
        IssueResponse updated = issue(1L, "PROJ-1", "inprogress", PERSONA_MEMBER_ID, 4);

        when(almClient.update(1L, firstAttempt, BEARER))
                .thenThrow(new AlmClient.VersionConflictException("버전 충돌"));
        when(almClient.update(1L, secondAttempt, BEARER)).thenReturn(updated);

        String result = issueTools.claimIssue("PROJ-1");

        assertThat(result).contains("PROJ-1").contains("inprogress");
        verify(almClient, times(2)).getByKey(eq("PROJ-1"), eq(BEARER));
        verify(almClient, times(1)).update(eq(1L), eq(firstAttempt), eq(BEARER));
        verify(almClient, times(1)).update(eq(1L), eq(secondAttempt), eq(BEARER));
        verify(auditService).record(eq(PERSONA_ID), eq(OWNER_MEMBER_ID), eq("claim_issue"), org.mockito.ArgumentMatchers.anyString(), eq(AuditStatus.OK));
    }

    @Test
    void claim_issue_does_not_retry_a_second_time_and_surfaces_error() {
        IssueResponse fetched = issue(1L, "PROJ-1", "todo", null, 2);
        when(almClient.getByKey("PROJ-1", BEARER)).thenReturn(fetched);
        when(almClient.update(eq(1L), org.mockito.ArgumentMatchers.any(), eq(BEARER)))
                .thenThrow(new AlmClient.VersionConflictException("버전 충돌"));

        String result = issueTools.claimIssue("PROJ-1");

        assertThat(result).startsWith("오류:").contains("버전 충돌");
        // 최초 시도 + 재시도 1회 = 정확히 2번
        verify(almClient, times(2)).update(eq(1L), org.mockito.ArgumentMatchers.any(), eq(BEARER));
        verify(auditService).record(eq(PERSONA_ID), eq(OWNER_MEMBER_ID), eq("claim_issue"), org.mockito.ArgumentMatchers.anyString(), eq(AuditStatus.ERROR));
    }

    // ---- create_issue: description <p> 래핑 ----

    @Test
    void toTipTapHtml_wraps_plain_text_paragraphs_and_preserves_existing_html() {
        assertThat(IssueTools.toTipTapHtml(null)).isNull();
        assertThat(IssueTools.toTipTapHtml("hello")).isEqualTo("<p>hello</p>");
        assertThat(IssueTools.toTipTapHtml("line1\nline2")).isEqualTo("<p>line1<br/>line2</p>");
        assertThat(IssueTools.toTipTapHtml("first\n\nsecond")).isEqualTo("<p>first</p><p>second</p>");
        assertThat(IssueTools.toTipTapHtml("<p>already html</p>")).isEqualTo("<p>already html</p>");
        assertThat(IssueTools.toTipTapHtml("  <div>x</div>")).isEqualTo("  <div>x</div>");
    }

    @Test
    void create_issue_wraps_plain_text_description_before_sending() {
        IssueResponse created = issue(99L, "PROJ-99", "todo", null, 0);
        when(almClient.create(eq(9L), org.mockito.ArgumentMatchers.any(), eq(BEARER))).thenReturn(created);

        String result = issueTools.createIssue(9L, "제목", "첫 문단\n\n둘째 문단", "task", "high", null, null, null);

        assertThat(result).isEqualTo("PROJ-99");
        IssueCreateRequest expected = new IssueCreateRequest(
                "제목", "<p>첫 문단</p><p>둘째 문단</p>", "task", null, "high", null, null, null);
        verify(almClient).create(eq(9L), eq(expected), eq(BEARER));
    }

    @Test
    void create_issue_passes_through_html_description_unwrapped() {
        IssueResponse created = issue(99L, "PROJ-99", "todo", null, 0);
        when(almClient.create(eq(9L), org.mockito.ArgumentMatchers.any(), eq(BEARER))).thenReturn(created);

        issueTools.createIssue(9L, "제목", "<p>이미 html</p>", null, null, null, null, null);

        IssueCreateRequest expected = new IssueCreateRequest(
                "제목", "<p>이미 html</p>", null, null, null, null, null, null);
        verify(almClient).create(eq(9L), eq(expected), eq(BEARER));
    }

    @Test
    void create_issue_resolves_assignee_slug_to_persona_member_id() {
        Persona persona = Persona.of(77L, "bot-a", PersonaRole.BACKEND, "봇A", null, null);
        when(personaRepository.findBySlug("bot-a")).thenReturn(Optional.of(persona));
        IssueResponse created = issue(99L, "PROJ-99", "todo", 77L, 0);
        when(almClient.create(eq(9L), org.mockito.ArgumentMatchers.any(), eq(BEARER))).thenReturn(created);

        issueTools.createIssue(9L, "제목", null, null, null, "bot-a", null, null);

        IssueCreateRequest expected = new IssueCreateRequest("제목", null, null, null, null, 77L, null, null);
        verify(almClient).create(eq(9L), eq(expected), eq(BEARER));
    }

    @Test
    void create_issue_with_unknown_assignee_slug_returns_error_and_never_calls_alm() {
        when(personaRepository.findBySlug("ghost")).thenReturn(Optional.empty());

        String result = issueTools.createIssue(9L, "제목", null, null, null, "ghost", null, null);

        assertThat(result).startsWith("오류:").contains("ghost");
        verify(almClient, never()).create(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        verify(auditService).record(eq(PERSONA_ID), eq(OWNER_MEMBER_ID), eq("create_issue"), org.mockito.ArgumentMatchers.anyString(), eq(AuditStatus.ERROR));
    }

    @Test
    void create_issue_resolves_parent_key_to_parent_id() {
        IssueResponse parent = issue(1L, "PROJ-1", "todo", null, 0);
        when(almClient.getByKey("PROJ-1", BEARER)).thenReturn(parent);
        IssueResponse created = issue(2L, "PROJ-2", "todo", null, 0);
        when(almClient.create(eq(9L), org.mockito.ArgumentMatchers.any(), eq(BEARER))).thenReturn(created);

        issueTools.createIssue(9L, "제목", null, null, null, null, null, "PROJ-1");

        verify(almClient).create(eq(9L), org.mockito.ArgumentMatchers.argThat(req -> req.details() != null && req.details().parentId().equals(1L)), eq(BEARER));
    }

    // ---- audit OK/ERROR ----

    @Test
    void add_comment_records_ok_audit_on_success() {
        IssueResponse fetched = issue(1L, "PROJ-1", "todo", null, 1);
        when(almClient.getByKey("PROJ-1", BEARER)).thenReturn(fetched);
        when(almClient.addComment(1L, "hello", BEARER)).thenReturn(new CommentResponse(5L, 1L, PERSONA_MEMBER_ID, "hello", null, null));

        String result = issueTools.addComment("PROJ-1", "hello");

        assertThat(result).contains("5");
        verify(auditService).record(eq(PERSONA_ID), eq(OWNER_MEMBER_ID), eq("add_comment"), org.mockito.ArgumentMatchers.anyString(), eq(AuditStatus.OK));
    }

    @Test
    void get_issue_records_error_audit_and_returns_plain_error_text_on_generic_failure() {
        when(almClient.getByKey("PROJ-1", BEARER)).thenThrow(new RuntimeException("이슈를 찾을 수 없습니다"));

        String result = issueTools.getIssue("PROJ-1");

        assertThat(result).isEqualTo("오류: 이슈를 찾을 수 없습니다");
        verify(auditService).record(eq(PERSONA_ID), eq(OWNER_MEMBER_ID), eq("get_issue"), org.mockito.ArgumentMatchers.anyString(), eq(AuditStatus.ERROR));
    }

    // ---- 503 구분 메시지 ----

    @Test
    void service_unavailable_produces_distinct_message_not_confused_with_forbidden() {
        when(almClient.getByKey("PROJ-1", BEARER)).thenThrow(new ServiceUnavailableException("org-service 다운"));

        String result = issueTools.getIssue("PROJ-1");

        assertThat(result).isEqualTo("권한 서비스/다운스트림 일시 장애 — 권한 없음이 아님, 잠시 후 재시도하세요: org-service 다운");
        verify(auditService).record(eq(PERSONA_ID), eq(OWNER_MEMBER_ID), eq("get_issue"), org.mockito.ArgumentMatchers.anyString(), eq(AuditStatus.ERROR));
    }

    // ---- log_work: workedOn 기본값 = 오늘 ----

    @Test
    void log_work_defaults_worked_on_to_today_when_absent() {
        IssueResponse fetched = issue(1L, "PROJ-1", "todo", null, 1);
        when(almClient.getByKey("PROJ-1", BEARER)).thenReturn(fetched);
        com.platform.agentservice.client.dto.WorklogResponse worklog =
                new com.platform.agentservice.client.dto.WorklogResponse(9L, 1L, PERSONA_MEMBER_ID, new BigDecimal("2"), null, LocalDate.now(), null);
        when(almClient.addWorklog(eq(1L), org.mockito.ArgumentMatchers.argThat(r -> r.workedOn().equals(LocalDate.now())), eq(BEARER)))
                .thenReturn(worklog);

        String result = issueTools.logWork("PROJ-1", new BigDecimal("2"), null, null);

        assertThat(result).contains("9");
    }

    // ---- link_pr: 구조화 코멘트 ----

    @Test
    void link_pr_adds_structured_comment_with_url_and_note() {
        IssueResponse fetched = issue(1L, "PROJ-1", "todo", null, 1);
        when(almClient.getByKey("PROJ-1", BEARER)).thenReturn(fetched);
        when(almClient.addComment(eq(1L), eq("🔗 PR 연결: https://example.com/pr/1\n리뷰 부탁드립니다"), eq(BEARER)))
                .thenReturn(new CommentResponse(6L, 1L, PERSONA_MEMBER_ID, "body", null, null));

        String result = issueTools.linkPr("PROJ-1", "https://example.com/pr/1", "리뷰 부탁드립니다");

        assertThat(result).contains("6");
    }

    private static IssueResponse issue(long id, String key, String status, Long assigneeId, int version) {
        return new IssueResponse(id, key, 9L, "제목", "설명", "task", status, "high", assigneeId, 1L,
                null, null, null, null, null, null, List.of(), List.of(), 0L, version, null, null, null);
    }
}
