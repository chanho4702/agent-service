package com.platform.agentservice.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agentservice.audit.AuditService;
import com.platform.agentservice.audit.AuditStatus;
import com.platform.agentservice.client.TokenService;
import com.platform.agentservice.client.WikiClient;
import com.platform.agentservice.client.dto.PageCreateRequest;
import com.platform.agentservice.client.dto.PageNode;
import com.platform.agentservice.client.dto.PageResponse;
import com.platform.agentservice.client.dto.PageUpdateRequest;
import com.platform.agentservice.client.dto.SpaceResponse;
import com.platform.agentservice.pat.PatPrincipal;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WikiToolsTest {

    private static final long OWNER_MEMBER_ID = 100L;
    private static final long PERSONA_ID = 5L;
    private static final long PERSONA_MEMBER_ID = 42L;
    private static final String BEARER = "Bearer persona-token";

    @Mock WikiClient wikiClient;
    @Mock TokenService tokenService;
    @Mock AuditService auditService;

    private WikiTools wikiTools;

    @BeforeEach
    void setUp() {
        Audited audited = new Audited(auditService);
        ObjectMapper objectMapper = new ObjectMapper();
        wikiTools = new WikiTools(wikiClient, tokenService, audited, objectMapper);

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

    // ---- list_spaces: 압축 요약 ----

    @Test
    void list_spaces_returns_compact_id_key_name_and_records_ok_audit() {
        when(wikiClient.listSpaces(BEARER)).thenReturn(List.of(
                new SpaceResponse(1L, "ENG", "엔지니어링", "설명", null),
                new SpaceResponse(2L, "PERSONAL", "개인", null, 42L)));

        String result = wikiTools.listSpaces();

        assertThat(result).contains("\"id\":1").contains("\"key\":\"ENG\"").contains("\"name\":\"엔지니어링\"");
        assertThat(result).doesNotContain("설명");
        verify(auditService).record(eq(PERSONA_ID), eq(OWNER_MEMBER_ID), eq("list_spaces"), anyString(), eq(AuditStatus.OK));
    }

    // ---- get_page ----

    @Test
    void get_page_returns_title_content_version_and_status() {
        when(wikiClient.getPage(7L, BEARER)).thenReturn(page(7L, 1L, null, "제목", "본문", 3, "page", "published"));

        String result = wikiTools.getPage(7L);

        assertThat(result).contains("\"title\":\"제목\"").contains("\"content\":\"본문\"")
                .contains("\"version\":3").contains("\"spaceId\":1").contains("\"status\":\"published\"");
        verify(auditService).record(eq(PERSONA_ID), eq(OWNER_MEMBER_ID), eq("get_page"), anyString(), eq(AuditStatus.OK));
    }

    // ---- find_pages: query 있으면 검색, 없으면 루트 목록 ----

    @Test
    void find_pages_with_query_calls_search_endpoint() {
        when(wikiClient.searchPages(1L, "장애", BEARER)).thenReturn(
                List.of(new PageNode(3L, "장애 대응", "page", "published")));

        String result = wikiTools.findPages(1L, "장애");

        assertThat(result).contains("\"id\":3").contains("\"title\":\"장애 대응\"").contains("\"type\":\"page\"");
        verify(wikiClient, never()).rootPages(org.mockito.ArgumentMatchers.anyLong(), anyString());
        verify(wikiClient).searchPages(1L, "장애", BEARER);
    }

    @Test
    void find_pages_without_query_calls_root_children_endpoint() {
        when(wikiClient.rootPages(1L, BEARER)).thenReturn(
                List.of(new PageNode(9L, "루트 문서", "folder", "published")));

        String result = wikiTools.findPages(1L, null);

        assertThat(result).contains("\"id\":9").contains("\"title\":\"루트 문서\"");
        verify(wikiClient).rootPages(1L, BEARER);
        verify(wikiClient, never()).searchPages(org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString());
    }

    @Test
    void find_pages_with_blank_query_treated_as_absent() {
        when(wikiClient.rootPages(1L, BEARER)).thenReturn(List.of());

        wikiTools.findPages(1L, "   ");

        verify(wikiClient).rootPages(1L, BEARER);
        verify(wikiClient, never()).searchPages(org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString());
    }

    // ---- create_page: draft 플래그 매핑 ----

    @Test
    void create_page_maps_draft_true_to_draft_status() {
        PageResponse created = page(50L, 1L, null, "초안 문서", "본문", 0, "page", "draft");
        when(wikiClient.createPage(any(), eq(BEARER))).thenReturn(created);

        String result = wikiTools.createPage(1L, null, "초안 문서", "본문", true);

        assertThat(result).contains("50").contains("초안 문서");
        PageCreateRequest expected = new PageCreateRequest(1L, null, "초안 문서", "본문", null, "draft");
        verify(wikiClient).createPage(eq(expected), eq(BEARER));
    }

    @Test
    void create_page_defaults_draft_absent_to_published_status() {
        PageResponse created = page(51L, 1L, 3L, "게시 문서", "본문", 0, "page", "published");
        when(wikiClient.createPage(any(), eq(BEARER))).thenReturn(created);

        wikiTools.createPage(1L, 3L, "게시 문서", "본문", null);

        PageCreateRequest expected = new PageCreateRequest(1L, 3L, "게시 문서", "본문", null, "published");
        verify(wikiClient).createPage(eq(expected), eq(BEARER));
    }

    // ---- update_page: 현재 parentId 유지, 제목 미지정 시 현재 제목 유지 ----

    @Test
    void update_page_keeps_current_parentId_and_falls_back_to_current_title_when_absent() {
        PageResponse current = page(7L, 1L, 5L, "현재 제목", "옛 본문", 2, "page", "published");
        when(wikiClient.getPage(7L, BEARER)).thenReturn(current);
        PageUpdateRequest expected = new PageUpdateRequest("현재 제목", "새 본문", 5L, 2, null);
        PageResponse updated = page(7L, 1L, 5L, "현재 제목", "새 본문", 3, "page", "published");
        when(wikiClient.updatePage(7L, expected, BEARER)).thenReturn(updated);

        String result = wikiTools.updatePage(7L, null, "새 본문", null);

        assertThat(result).contains("7").contains("version=3");
        verify(wikiClient).updatePage(eq(7L), eq(expected), eq(BEARER));
    }

    @Test
    void update_page_uses_given_title_when_present() {
        PageResponse current = page(7L, 1L, 5L, "현재 제목", "옛 본문", 2, "page", "published");
        when(wikiClient.getPage(7L, BEARER)).thenReturn(current);
        PageUpdateRequest expected = new PageUpdateRequest("바뀐 제목", "새 본문", 5L, 2, "제목·본문 수정");
        PageResponse updated = page(7L, 1L, 5L, "바뀐 제목", "새 본문", 3, "page", "published");
        when(wikiClient.updatePage(7L, expected, BEARER)).thenReturn(updated);

        wikiTools.updatePage(7L, "바뀐 제목", "새 본문", "제목·본문 수정");

        verify(wikiClient).updatePage(eq(7L), eq(expected), eq(BEARER));
    }

    @Test
    void update_page_retries_once_after_409_conflict_recomputing_parentId_and_version_from_fresh_state() {
        PageResponse staleFetch = page(7L, 1L, 5L, "제목", "옛 본문", 2, "page", "published");
        PageResponse freshFetch = page(7L, 1L, 9L, "제목", "동시 편집된 본문", 3, "page", "published");
        when(wikiClient.getPage(7L, BEARER)).thenReturn(staleFetch, freshFetch);

        PageUpdateRequest firstAttempt = new PageUpdateRequest("제목", "새 본문", 5L, 2, null);
        PageUpdateRequest secondAttempt = new PageUpdateRequest("제목", "새 본문", 9L, 3, null);
        PageResponse updated = page(7L, 1L, 9L, "제목", "새 본문", 4, "page", "published");

        when(wikiClient.updatePage(7L, firstAttempt, BEARER))
                .thenThrow(new WikiClient.VersionConflictException("버전 충돌"));
        when(wikiClient.updatePage(7L, secondAttempt, BEARER)).thenReturn(updated);

        String result = wikiTools.updatePage(7L, null, "새 본문", null);

        assertThat(result).contains("version=4");
        verify(wikiClient, times(2)).getPage(eq(7L), eq(BEARER));
        verify(wikiClient, times(1)).updatePage(eq(7L), eq(firstAttempt), eq(BEARER));
        verify(wikiClient, times(1)).updatePage(eq(7L), eq(secondAttempt), eq(BEARER));
        verify(auditService).record(eq(PERSONA_ID), eq(OWNER_MEMBER_ID), eq("update_page"), anyString(), eq(AuditStatus.OK));
    }

    @Test
    void update_page_does_not_retry_a_second_time_and_surfaces_error() {
        PageResponse current = page(7L, 1L, 5L, "제목", "옛 본문", 2, "page", "published");
        when(wikiClient.getPage(7L, BEARER)).thenReturn(current);
        when(wikiClient.updatePage(eq(7L), any(), eq(BEARER)))
                .thenThrow(new WikiClient.VersionConflictException("버전 충돌"));

        String result = wikiTools.updatePage(7L, null, "새 본문", null);

        assertThat(result).startsWith("오류:").contains("버전 충돌");
        verify(wikiClient, times(2)).updatePage(eq(7L), any(), eq(BEARER));
        verify(auditService).record(eq(PERSONA_ID), eq(OWNER_MEMBER_ID), eq("update_page"), anyString(), eq(AuditStatus.ERROR));
    }

    // ---- append_to_page: 기존 본문 보존 ----

    @Test
    void append_to_page_preserves_existing_body_and_appends_new_section() {
        PageResponse current = page(7L, 1L, 5L, "작업 기록", "기존 내용 첫 줄\n기존 내용 둘째 줄", 2, "page", "published");
        when(wikiClient.getPage(7L, BEARER)).thenReturn(current);
        String expectedContent = "기존 내용 첫 줄\n기존 내용 둘째 줄\n\n새 작업 로그";
        PageUpdateRequest expected = new PageUpdateRequest("작업 기록", expectedContent, 5L, 2, "로그 추가");
        PageResponse updated = page(7L, 1L, 5L, "작업 기록", expectedContent, 3, "page", "published");
        when(wikiClient.updatePage(7L, expected, BEARER)).thenReturn(updated);

        String result = wikiTools.appendToPage(7L, "새 작업 로그", "로그 추가");

        assertThat(result).contains("7").contains("version=3");
        verify(wikiClient).updatePage(eq(7L), org.mockito.ArgumentMatchers.argThat(req ->
                req.content().equals(expectedContent) && req.content().contains("기존 내용 첫 줄")
                        && req.content().contains("기존 내용 둘째 줄") && req.content().contains("새 작업 로그")
        ), eq(BEARER));
        verify(wikiClient).updatePage(eq(7L), eq(expected), eq(BEARER));
    }

    @Test
    void append_to_page_recomputes_base_content_from_fresh_state_on_409_retry() {
        PageResponse staleFetch = page(7L, 1L, 5L, "제목", "옛 본문", 2, "page", "published");
        // 재조회 시점에는 다른 편집자가 이미 본문을 바꿔둔 상태 — 그 위에 이어붙여야 한다.
        PageResponse freshFetch = page(7L, 1L, 5L, "제목", "동시 편집자가 바꾼 본문", 3, "page", "published");
        when(wikiClient.getPage(7L, BEARER)).thenReturn(staleFetch, freshFetch);

        PageUpdateRequest firstAttempt = new PageUpdateRequest("제목", "옛 본문\n\n추가분", 5L, 2, null);
        PageUpdateRequest secondAttempt = new PageUpdateRequest("제목", "동시 편집자가 바꾼 본문\n\n추가분", 5L, 3, null);
        PageResponse updated = page(7L, 1L, 5L, "제목", "동시 편집자가 바꾼 본문\n\n추가분", 4, "page", "published");

        when(wikiClient.updatePage(7L, firstAttempt, BEARER))
                .thenThrow(new WikiClient.VersionConflictException("버전 충돌"));
        when(wikiClient.updatePage(7L, secondAttempt, BEARER)).thenReturn(updated);

        String result = wikiTools.appendToPage(7L, "추가분", null);

        assertThat(result).contains("version=4");
        verify(wikiClient, times(1)).updatePage(eq(7L), eq(firstAttempt), eq(BEARER));
        verify(wikiClient, times(1)).updatePage(eq(7L), eq(secondAttempt), eq(BEARER));
    }

    // ---- audit ERROR / 503 구분 메시지 ----

    @Test
    void get_page_records_error_audit_and_returns_plain_error_text_on_generic_failure() {
        when(wikiClient.getPage(7L, BEARER)).thenThrow(new RuntimeException("문서를 찾을 수 없습니다"));

        String result = wikiTools.getPage(7L);

        assertThat(result).isEqualTo("오류: 문서를 찾을 수 없습니다");
        verify(auditService).record(eq(PERSONA_ID), eq(OWNER_MEMBER_ID), eq("get_page"), anyString(), eq(AuditStatus.ERROR));
    }

    @Test
    void service_unavailable_produces_distinct_message_not_confused_with_forbidden() {
        when(wikiClient.getPage(7L, BEARER)).thenThrow(new ServiceUnavailableException("wiki-backend 다운"));

        String result = wikiTools.getPage(7L);

        assertThat(result).isEqualTo("권한 서비스/다운스트림 일시 장애 — 권한 없음이 아님, 잠시 후 재시도하세요: wiki-backend 다운");
        verify(auditService).record(eq(PERSONA_ID), eq(OWNER_MEMBER_ID), eq("get_page"), anyString(), eq(AuditStatus.ERROR));
    }

    // ---- ToolActor ----

    @Test
    void toolActor_throws_forbidden_without_pat_principal() {
        SecurityContextHolder.clearContext();
        org.junit.jupiter.api.Assertions.assertThrows(ForbiddenException.class, ToolActor::current);
    }

    private static PageResponse page(long id, long spaceId, Long parentId, String title, String content,
                                      int version, String type, String status) {
        return new PageResponse(id, spaceId, parentId, title, content, version, type, status);
    }
}
