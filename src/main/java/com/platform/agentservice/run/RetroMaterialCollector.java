package com.platform.agentservice.run;

import com.platform.agentservice.client.AlmClient;
import com.platform.agentservice.client.WikiClient;
import com.platform.agentservice.client.dto.CommentResponse;
import com.platform.agentservice.client.dto.IssueResponse;
import com.platform.agentservice.worker.WorkerJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 회고 자료 수집(P4b D-P4b-4, AGP-58) — run 요약만 보던 RETRO에 최근 24시간 ALM 이슈 변경·코멘트·회의록 스페이스 문서를 더한다.
 * 전부 진행자(run 소유 페르소나) bearer로 읽으므로 그 페르소나가 볼 수 있는 것만 들어온다.
 *
 * <p><b>블록마다 독립 실패</b>: 한 블록이 실패하면 그 목록만 null(프롬프트에서 블록 생략) + warn 로그. 회고를 막지 않는다 — 자료가 덜
 * 들어간 회고가 회고가 없는 것보다 낫다. 이 클래스는 던지지 않는다.
 *
 * <p><b>창</b>: 기존 회고 run 요약과 같은 "지금부터 24시간 전"({@link MeetingService#RETRO_WINDOW}). AQL 상대 날짜 {@code -1d}도
 * alm 쪽에서 now − 1일(Asia/Seoul 기준 시각 연산)이라 같은 창이다.
 *
 * <p><b>출처(alm·wiki 실측)</b>: 이슈 변경 = {@code POST /api/alm/issues/query}(AQL {@code created/updated/resolved >= -1d}).
 * 코멘트 = 이슈별 {@code GET /api/alm/issues/{id}/comments}를 createdAt으로 거른다 — alm에 프로젝트 단위 코멘트 피드가 없고 코멘트는
 * 이슈 {@code updated}를 바꾸지 않아서, 후보를 "최근 변경 이슈 ∪ 진행 중 이슈"로 잡는다(상한 {@value #COMMENT_CANDIDATE_LIMIT}건).
 * 위키 = {@code GET /api/wiki/spaces/{spaceId}/pages/recent}(수정순)를 updatedAt으로 거른다.
 *
 * <p><b>출력 위생</b>: 라인은 한 줄로 접고 꺾쇠({@code < >})를 무력화한다 — 제목·코멘트 한 줄이 데이터 블록 태그를 닫아 규약 영역으로
 * 새어 나오지 못하게. 코멘트 본문은 앞 {@value #COMMENT_PREVIEW}자만.
 */
@Slf4j
@Component
public class RetroMaterialCollector {

    static final int ISSUE_LIMIT = 30;
    static final int ACTIVE_ISSUE_LIMIT = 20;
    static final int COMMENT_CANDIDATE_LIMIT = 40;
    static final int COMMENT_LINE_LIMIT = 20;
    static final int PAGE_LIMIT = 15;
    static final int TITLE_PREVIEW = 60;
    static final int COMMENT_PREVIEW = 80;
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZONE);

    private final AlmClient almClient;
    private final WikiClient wikiClient;
    private final Clock clock;

    @Autowired
    public RetroMaterialCollector(AlmClient almClient, WikiClient wikiClient) {
        this(almClient, wikiClient, Clock.systemUTC());
    }

    RetroMaterialCollector(AlmClient almClient, WikiClient wikiClient, Clock clock) {
        this.almClient = almClient;
        this.wikiClient = wikiClient;
        this.clock = clock;
    }

    public WorkerJob.RetroData collect(long projectId, long spaceId, String bearer) {
        Instant since = clock.instant().minus(MeetingService.RETRO_WINDOW);
        String projectKey = projectKey(projectId, bearer);

        List<IssueResponse> changed = projectKey == null ? null : changedIssues(projectKey, projectId, bearer);
        List<String> issueLines = changed == null ? null : changed.stream()
                .map(i -> i.key() + " · " + clean(i.status(), 40) + " · " + clean(i.title(), TITLE_PREVIEW))
                .toList();
        List<String> commentLines = projectKey == null ? null : commentLines(projectKey, projectId, changed, since, bearer);
        return new WorkerJob.RetroData(issueLines, commentLines, pageLines(spaceId, since, bearer));
    }

    // ---- 블록별 ----

    private String projectKey(long projectId, String bearer) {
        try {
            return almClient.getProject(projectId, bearer).key();
        } catch (Exception e) {
            log.warn("회고 자료: 프로젝트 조회 실패 — 이슈 변경·코멘트 블록 생략: projectId={} : {}", projectId, e.getMessage());
            return null;
        }
    }

    private List<IssueResponse> changedIssues(String projectKey, long projectId, String bearer) {
        try {
            return inProject(almClient.query("project = " + quote(projectKey)
                    + " AND (created >= -1d OR updated >= -1d OR resolved >= -1d) ORDER BY updated DESC",
                    0, ISSUE_LIMIT, bearer).items(), projectId);
        } catch (Exception e) {
            log.warn("회고 자료: 최근 이슈 변경 조회 실패 — 블록 생략: projectId={} : {}", projectId, e.getMessage());
            return null;
        }
    }

    private List<String> commentLines(String projectKey, long projectId, List<IssueResponse> changed, Instant since,
                                      String bearer) {
        Map<Long, IssueResponse> candidates = new LinkedHashMap<>();
        if (changed != null) {
            changed.forEach(i -> candidates.putIfAbsent(i.id(), i));
        }
        try {
            inProject(almClient.query("project = " + quote(projectKey) + " AND statusCategory = active ORDER BY updated DESC",
                    0, ACTIVE_ISSUE_LIMIT, bearer).items(), projectId)
                    .forEach(i -> candidates.putIfAbsent(i.id(), i));
        } catch (Exception e) {
            if (changed == null) {
                log.warn("회고 자료: 코멘트 후보 조회 실패 — 블록 생략: projectId={} : {}", projectId, e.getMessage());
                return null;
            }
            log.warn("회고 자료: 진행 중 이슈 조회 실패 — 최근 변경 이슈의 코멘트만 셉니다: projectId={} : {}", projectId, e.getMessage());
        }
        record Tally(IssueResponse issue, long count, CommentResponse latest) {
        }
        List<Tally> tallies = new ArrayList<>();
        int failures = 0;
        for (IssueResponse issue : candidates.values().stream().limit(COMMENT_CANDIDATE_LIMIT).toList()) {
            try {
                List<CommentResponse> recent = almClient.comments(issue.id(), bearer).stream()
                        .filter(c -> c.createdAt() != null && !c.createdAt().isBefore(since))
                        .toList();
                if (!recent.isEmpty()) {
                    tallies.add(new Tally(issue, recent.size(), recent.get(recent.size() - 1)));
                }
            } catch (Exception e) {
                failures++;
            }
        }
        if (failures > 0) {
            log.warn("회고 자료: 코멘트 조회 실패 {}건(나머지 이슈만 셉니다): projectId={}", failures, projectId);
        }
        if (failures > 0 && failures == Math.min(candidates.size(), COMMENT_CANDIDATE_LIMIT)) {
            return null;
        }
        return tallies.stream()
                .sorted(Comparator.comparingLong(Tally::count).reversed().thenComparing(t -> t.issue().key()))
                .limit(COMMENT_LINE_LIMIT)
                .map(t -> t.issue().key() + " · 코멘트 " + t.count() + "건 · 최근: " + clean(stripTags(t.latest().body()), COMMENT_PREVIEW))
                .toList();
    }

    private List<String> pageLines(long spaceId, Instant since, String bearer) {
        try {
            return wikiClient.recentPages(spaceId, PAGE_LIMIT, bearer).stream()
                    .filter(p -> p.updatedAt() != null && !p.updatedAt().isBefore(since))
                    .limit(PAGE_LIMIT)
                    .map(p -> clean(p.title(), TITLE_PREVIEW) + " · pageId=" + p.id() + " · " + STAMP.format(p.updatedAt()))
                    .toList();
        } catch (Exception e) {
            log.warn("회고 자료: 최근 위키 문서 조회 실패 — 블록 생략: spaceId={} : {}", spaceId, e.getMessage());
            return null;
        }
    }

    // ---- 다듬기 ----

    /** 프로젝트 이름이 다른 프로젝트 키와 같으면 AQL이 둘 다 푼다 — 대상 프로젝트만 남긴다. */
    private static List<IssueResponse> inProject(List<IssueResponse> items, long projectId) {
        return items == null ? List.of() : items.stream().filter(i -> i.projectId() == projectId).toList();
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static String stripTags(String html) {
        return html == null ? null : html.replaceAll("<[^>]*>", " ")
                .replace("&nbsp;", " ").replace("&lt;", "‹").replace("&gt;", "›").replace("&amp;", "&");
    }

    /** 한 줄로 접고, 꺾쇠를 무력화하고, {@code max}자에서 자른다(서로게이트 쌍은 쪼개지 않는다). */
    static String clean(String text, int max) {
        if (text == null || text.isBlank()) {
            return "(없음)";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim().replace('<', '‹').replace('>', '›');
        if (oneLine.length() <= max) {
            return oneLine;
        }
        int end = max;
        if (Character.isHighSurrogate(oneLine.charAt(end - 1))) {
            end--;
        }
        return oneLine.substring(0, end) + "…";
    }
}
