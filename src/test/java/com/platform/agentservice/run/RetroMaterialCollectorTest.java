package com.platform.agentservice.run;

import com.platform.agentservice.client.AlmClient;
import com.platform.agentservice.client.WikiClient;
import com.platform.agentservice.client.dto.CommentResponse;
import com.platform.agentservice.client.dto.IssuePageResponse;
import com.platform.agentservice.client.dto.IssueResponse;
import com.platform.agentservice.client.dto.ProjectResponse;
import com.platform.agentservice.worker.WorkerJob;
import com.platform.common.error.ServiceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 회고 자료 수집(D-P4b-4) — 블록마다 독립 실패(null), 24시간 창, 상한, 한 줄·꺾쇠 무력화. */
@ExtendWith(MockitoExtension.class)
class RetroMaterialCollectorTest {

    static final long PROJECT = 1L;
    static final long SPACE = 7L;
    static final String BEARER = "Bearer host";
    static final Instant NOW = Instant.parse("2026-09-29T09:00:00Z");

    @Mock AlmClient almClient;
    @Mock WikiClient wikiClient;

    RetroMaterialCollector collector;

    @BeforeEach
    void setUp() {
        collector = new RetroMaterialCollector(almClient, wikiClient, Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(almClient.getProject(PROJECT, BEARER)).thenReturn(new ProjectResponse(PROJECT, "AGP", "agent"));
    }

    private static IssueResponse issue(long id, String key, long projectId, String status, String title) {
        return new IssueResponse(id, key, projectId, title, null, "task", status, "high", null, 1L, null, null, null, null,
                null, null, List.of(), List.of(), 0L, 1, null, null, null);
    }

    private static IssuePageResponse page(IssueResponse... items) {
        return new IssuePageResponse(List.of(items), 0, items.length, items.length);
    }

    private static CommentResponse comment(long issueId, String body, Instant at) {
        return new CommentResponse(1L, issueId, 3L, body, at, at);
    }

    private void changed(IssueResponse... items) {
        when(almClient.query(contains("created >= -1d"), eq(0), eq(RetroMaterialCollector.ISSUE_LIMIT), eq(BEARER)))
                .thenReturn(page(items));
    }

    private void active(IssueResponse... items) {
        lenient().when(almClient.query(contains("statusCategory = active"), eq(0), eq(RetroMaterialCollector.ACTIVE_ISSUE_LIMIT),
                eq(BEARER))).thenReturn(page(items));
    }

    @Test
    void collects_issue_changes_comment_counts_and_recent_pages_from_the_last_24_hours() {
        changed(issue(11L, "AGP-9", PROJECT, "done", "로그인 API <b>굵게</b>"),
                issue(99L, "OTH-1", 2L, "todo", "이름이 같은 다른 프로젝트"));
        active(issue(12L, "AGP-10", PROJECT, "inprogress", "진행 중"));
        when(almClient.comments(11L, BEARER)).thenReturn(List.of(
                comment(11L, "오래된 코멘트", NOW.minus(Duration.ofHours(30))),
                comment(11L, "<p>첫\n코멘트</p>", NOW.minus(Duration.ofHours(5))),
                comment(11L, "<p>테스트 통과 &amp; 리뷰 대기</p>", NOW.minus(Duration.ofHours(1)))));
        when(almClient.comments(12L, BEARER)).thenReturn(List.of(comment(12L, "막힘", NOW.minus(Duration.ofHours(2)))));
        when(wikiClient.recentPages(SPACE, RetroMaterialCollector.PAGE_LIMIT, BEARER)).thenReturn(List.of(
                new WikiClient.RecentPage(501L, "[회고] 09-28", NOW.minus(Duration.ofHours(15))),
                new WikiClient.RecentPage(400L, "지난주 문서", NOW.minus(Duration.ofDays(3)))));

        WorkerJob.RetroData data = collector.collect(PROJECT, SPACE, BEARER);

        verify(almClient).query(eq("project = \"AGP\" AND (created >= -1d OR updated >= -1d OR resolved >= -1d) ORDER BY updated DESC"),
                eq(0), eq(30), eq(BEARER));
        // 다른 프로젝트 이슈는 빠지고, 꺾쇠는 무력화된다(블록 태그를 닫지 못하게).
        assertThat(data.issueChanges()).containsExactly("AGP-9 · done · 로그인 API ‹b›굵게‹/b›");
        assertThat(data.commentCounts()).containsExactly(
                "AGP-9 · 코멘트 2건 · 최근: 테스트 통과 & 리뷰 대기",
                "AGP-10 · 코멘트 1건 · 최근: 막힘");
        assertThat(data.recentPages()).containsExactly("[회고] 09-28 · pageId=501 · 09-29 03:00");
        verify(almClient, never()).comments(eq(99L), anyString());
    }

    @Test
    void each_block_fails_independently_to_null() {
        when(almClient.query(anyString(), eq(0), eq(RetroMaterialCollector.ISSUE_LIMIT), anyString()))
                .thenThrow(new ServiceUnavailableException("alm down"));
        active();
        when(wikiClient.recentPages(anyLong(), eq(RetroMaterialCollector.PAGE_LIMIT), anyString()))
                .thenThrow(new ServiceUnavailableException("wiki down"));

        WorkerJob.RetroData data = collector.collect(PROJECT, SPACE, BEARER);

        assertThat(data.issueChanges()).isNull();
        // 진행 중 이슈 조회는 됐으니 코멘트 블록은 살아 있다(해당 없음 = 빈 목록).
        assertThat(data.commentCounts()).isEmpty();
        assertThat(data.recentPages()).isNull();
    }

    @Test
    void project_lookup_failure_drops_both_alm_blocks_but_keeps_the_wiki_block() {
        when(almClient.getProject(PROJECT, BEARER)).thenThrow(new ServiceUnavailableException("alm down"));
        when(wikiClient.recentPages(SPACE, RetroMaterialCollector.PAGE_LIMIT, BEARER)).thenReturn(List.of());

        WorkerJob.RetroData data = collector.collect(PROJECT, SPACE, BEARER);

        assertThat(data.issueChanges()).isNull();
        assertThat(data.commentCounts()).isNull();
        assertThat(data.recentPages()).isEmpty();
    }

    @Test
    void comment_block_is_null_when_every_comment_fetch_fails() {
        changed(issue(11L, "AGP-9", PROJECT, "done", "t"));
        active();
        when(almClient.comments(11L, BEARER)).thenThrow(new ServiceUnavailableException("down"));
        when(wikiClient.recentPages(SPACE, RetroMaterialCollector.PAGE_LIMIT, BEARER)).thenReturn(List.of());

        assertThat(collector.collect(PROJECT, SPACE, BEARER).commentCounts()).isNull();
    }

    @Test
    void comment_lines_are_capped_sorted_by_count_and_previews_are_cut_at_80_chars() {
        IssueResponse[] issues = IntStream.rangeClosed(1, 25)
                .mapToObj(n -> issue(n, "AGP-" + n, PROJECT, "inprogress", "t" + n)).toArray(IssueResponse[]::new);
        changed(issues);
        active();
        String longBody = "가".repeat(100);
        for (int n = 1; n <= 25; n++) {
            int count = n == 7 ? 3 : 1;
            List<CommentResponse> cs = IntStream.range(0, count)
                    .mapToObj(i -> comment(0L, longBody, NOW.minus(Duration.ofHours(1)))).toList();
            when(almClient.comments((long) n, BEARER)).thenReturn(cs);
        }
        when(wikiClient.recentPages(SPACE, RetroMaterialCollector.PAGE_LIMIT, BEARER)).thenReturn(List.of());

        List<String> lines = collector.collect(PROJECT, SPACE, BEARER).commentCounts();

        assertThat(lines).hasSize(RetroMaterialCollector.COMMENT_LINE_LIMIT);
        assertThat(lines.get(0)).startsWith("AGP-7 · 코멘트 3건 · 최근: ").endsWith("가".repeat(80) + "…");
    }

    @Test
    void clean_folds_whitespace_neutralises_angle_brackets_and_keeps_surrogate_pairs_whole() {
        assertThat(RetroMaterialCollector.clean("a\n  b\t<c>", 60)).isEqualTo("a b ‹c›");
        assertThat(RetroMaterialCollector.clean(null, 60)).isEqualTo("(없음)");
        String emoji = "x".repeat(59) + "😀";
        assertThat(RetroMaterialCollector.clean(emoji + "tail", 60)).isEqualTo("x".repeat(59) + "…");
    }
}
