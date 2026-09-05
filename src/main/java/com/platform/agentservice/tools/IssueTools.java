package com.platform.agentservice.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agentservice.client.AlmClient;
import com.platform.agentservice.client.TokenService;
import com.platform.agentservice.client.dto.CommentResponse;
import com.platform.agentservice.client.dto.IssueCreateRequest;
import com.platform.agentservice.client.dto.IssueDetailsRequest;
import com.platform.agentservice.client.dto.IssuePageResponse;
import com.platform.agentservice.client.dto.IssueResponse;
import com.platform.agentservice.client.dto.IssueUpdateRequest;
import com.platform.agentservice.client.dto.WorklogRequest;
import com.platform.agentservice.client.dto.WorklogResponse;
import com.platform.agentservice.pat.PatPrincipal;
import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRepository;
import com.platform.common.error.NotFoundException;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ALM 이슈 도구 8종(S10). 전부 페르소나 서비스 토큰({@link TokenService#bearerFor})으로
 * alm-backend를 호출하고, {@link Audited}로 감사 로그를 남긴다. 각 메서드는 가장 먼저
 * {@link ToolActor#current()}로 호출자를 확인한다 — bearer 토큰을 만들려면 어차피
 * {@code actor.personaMemberId()}가 필요하고, {@link Audited#run}도 내부적으로 다시
 * 확인하므로 이중 방어가 된다.
 *
 * <p><b>full-replace 주의(S10)</b>: alm-backend {@code PUT /api/alm/issues/{id}}는
 * title/type/status/priority/expectedVersion을 {@code @NotBlank}/{@code @NotNull}로
 * 강제하고, {@code assigneeId}는 null이면 담당자를 해제한다. {@code claim_issue}/
 * {@code update_issue_status}는 그래서 GET으로 가져온 현재 값을 그대로 채워 넣고
 * 바꿀 필드만 덮어쓴다. {@code details}는 항상 null로 보낸다 — alm-backend가 details가
 * null이면 parentId/sprintId/dueDate/estimateHours/resolution/fixVersionId/labels/
 * componentIds를 기존 값 그대로 보존하기 때문에(실측, IssueService.update), 매번 다시
 * 채워 넣을 필요가 없다.
 */
@Component
public class IssueTools {

    private final AlmClient almClient;
    private final TokenService tokenService;
    private final PersonaRepository personaRepository;
    private final Audited audited;
    private final ObjectMapper objectMapper;

    public IssueTools(AlmClient almClient, TokenService tokenService, PersonaRepository personaRepository,
                       Audited audited, ObjectMapper objectMapper) {
        this.almClient = almClient;
        this.tokenService = tokenService;
        this.personaRepository = personaRepository;
        this.audited = audited;
        this.objectMapper = objectMapper;
    }

    @McpTool(name = "search_issues", description = "조건에 맞는 ALM 이슈를 검색한다(키·제목·상태·담당자·우선순위 요약).")
    public String searchIssues(
            @McpToolParam(description = "프로젝트 id(생략 시 접근 가능한 프로젝트 전체)", required = false) Long projectId,
            @McpToolParam(description = "제목/본문 검색어", required = false) String text,
            @McpToolParam(description = "상태 id 목록(예: todo, inprogress, done)", required = false) List<String> statuses,
            @McpToolParam(description = "담당자 memberId 문자열, 또는 \"unassigned\"", required = false) String assignee,
            @McpToolParam(description = "라벨 목록", required = false) List<String> labels,
            @McpToolParam(description = "페이지 번호(0부터, 생략 시 0)", required = false) Integer page) {
        PatPrincipal actor = ToolActor.current();
        String summary = "projectId=" + projectId + " text=" + text + " assignee=" + assignee + " page=" + page;
        return audited.run("search_issues", summary, () -> {
            String bearer = tokenService.bearerFor(actor.personaMemberId());
            IssuePageResponse result = almClient.search(projectId, text, statuses, assignee, labels, page, bearer);
            return writeJson(searchSummary(result));
        });
    }

    @McpTool(name = "get_issue", description = "이슈 상세 + 최근 코멘트 10개 + 워크로그 합계를 조회한다.")
    public String getIssue(@McpToolParam(description = "이슈 키(예: PROJ-123)", required = true) String issueKey) {
        PatPrincipal actor = ToolActor.current();
        return audited.run("get_issue", issueKey, () -> {
            String bearer = tokenService.bearerFor(actor.personaMemberId());
            IssueResponse issue = almClient.getByKey(issueKey, bearer);
            List<CommentResponse> comments = almClient.comments(issue.id(), bearer);
            List<CommentResponse> recentComments = comments.size() > 10
                    ? comments.subList(comments.size() - 10, comments.size())
                    : comments;
            List<WorklogResponse> worklogs = almClient.worklogs(issue.id(), bearer);
            BigDecimal totalHours = worklogs.stream()
                    .map(WorklogResponse::hours)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("issue", issue);
            result.put("recentComments", recentComments);
            result.put("worklogTotalHours", totalHours);
            return writeJson(result);
        });
    }

    @McpTool(name = "create_issue", description = "ALM 프로젝트에 새 이슈를 생성한다.")
    public String createIssue(
            @McpToolParam(description = "프로젝트 id", required = true) long projectId,
            @McpToolParam(description = "이슈 제목", required = true) String title,
            @McpToolParam(description = "설명(일반 텍스트 또는 이미 HTML). 일반 텍스트면 문단 단위로 <p>로 감싼다", required = false) String description,
            @McpToolParam(description = "이슈 타입(task/story/bug/epic/subtask), 생략 시 프로젝트 기본값", required = false) String type,
            @McpToolParam(description = "우선순위(highest..lowest), 생략 시 프로젝트 기본값", required = false) String priority,
            @McpToolParam(description = "담당자 페르소나 slug(선택)", required = false) String assigneeSlug,
            @McpToolParam(description = "라벨 목록", required = false) List<String> labels,
            @McpToolParam(description = "상위 이슈 키(에픽 등, 선택)", required = false) String parentKey) {
        PatPrincipal actor = ToolActor.current();
        String summary = "projectId=" + projectId + " title=" + title;
        return audited.run("create_issue", summary, () -> {
            String bearer = tokenService.bearerFor(actor.personaMemberId());
            Long assigneeId = resolveAssigneeId(assigneeSlug);
            Long parentId = resolveParentId(parentKey, bearer);
            IssueDetailsRequest details = (parentId != null || (labels != null && !labels.isEmpty()))
                    ? new IssueDetailsRequest(parentId, null, null, null, null, null, labels, null)
                    : null;
            IssueCreateRequest request = new IssueCreateRequest(
                    title, toTipTapHtml(description), type, null, priority, assigneeId, details, null);
            IssueResponse created = almClient.create(projectId, request, bearer);
            return created.key();
        });
    }

    @McpTool(name = "claim_issue", description = "이슈를 현재 페르소나에게 배정하고 진행중 상태로 전환한다.")
    public String claimIssue(@McpToolParam(description = "이슈 키", required = true) String issueKey) {
        PatPrincipal actor = ToolActor.current();
        return audited.run("claim_issue", "claim " + issueKey, () -> {
            String bearer = tokenService.bearerFor(actor.personaMemberId());
            IssueResponse issue = almClient.getByKey(issueKey, bearer);
            IssueResponse updated = updateWithRetry(issue, actor.personaMemberId(), "inprogress", bearer);
            return "이슈 " + updated.key() + " 담당자 지정 완료 (상태: " + updated.status() + ")";
        });
    }

    @McpTool(name = "update_issue_status", description = "이슈 상태를 전환한다(프로젝트 스킴에 정의된 전이만 허용).")
    public String updateIssueStatus(
            @McpToolParam(description = "이슈 키", required = true) String issueKey,
            @McpToolParam(description = "전환할 상태 id", required = true) String status) {
        PatPrincipal actor = ToolActor.current();
        return audited.run("update_issue_status", issueKey + " -> " + status, () -> {
            String bearer = tokenService.bearerFor(actor.personaMemberId());
            IssueResponse issue = almClient.getByKey(issueKey, bearer);
            IssueResponse updated = updateWithRetry(issue, issue.assigneeId(), status, bearer);
            return "이슈 " + updated.key() + " 상태 변경 완료: " + updated.status();
        });
    }

    @McpTool(name = "add_comment", description = "이슈에 코멘트를 남긴다(조회 권한만 있어도 가능).")
    public String addComment(
            @McpToolParam(description = "이슈 키", required = true) String issueKey,
            @McpToolParam(description = "코멘트 본문", required = true) String body) {
        PatPrincipal actor = ToolActor.current();
        return audited.run("add_comment", issueKey + ": " + body, () -> {
            String bearer = tokenService.bearerFor(actor.personaMemberId());
            IssueResponse issue = almClient.getByKey(issueKey, bearer);
            CommentResponse comment = almClient.addComment(issue.id(), body, bearer);
            return "코멘트 등록 완료 (id=" + comment.id() + ")";
        });
    }

    @McpTool(name = "log_work", description = "이슈에 작업시간을 기록한다(수정 권한 필요).")
    public String logWork(
            @McpToolParam(description = "이슈 키", required = true) String issueKey,
            @McpToolParam(description = "작업 시간(시간 단위, 0보다 커야 함)", required = true) BigDecimal hours,
            @McpToolParam(description = "작업 코멘트(선택)", required = false) String comment,
            @McpToolParam(description = "작업일(생략 시 오늘)", required = false) LocalDate workedOn) {
        PatPrincipal actor = ToolActor.current();
        return audited.run("log_work", issueKey + " " + hours + "h", () -> {
            String bearer = tokenService.bearerFor(actor.personaMemberId());
            IssueResponse issue = almClient.getByKey(issueKey, bearer);
            LocalDate effectiveDate = workedOn != null ? workedOn : LocalDate.now();
            WorklogResponse worklog = almClient.addWorklog(
                    issue.id(), new WorklogRequest(hours, comment, effectiveDate), bearer);
            return "워크로그 등록 완료 (id=" + worklog.id() + ", " + worklog.hours() + "h)";
        });
    }

    @McpTool(name = "link_pr", description = "이슈에 PR 링크를 구조화 코멘트로 남긴다(P1 잠정 — 원격 링크 엔티티는 P2에서).")
    public String linkPr(
            @McpToolParam(description = "이슈 키", required = true) String issueKey,
            @McpToolParam(description = "PR URL", required = true) String url,
            @McpToolParam(description = "부가 설명(선택)", required = false) String note) {
        PatPrincipal actor = ToolActor.current();
        String body = "🔗 PR 연결: " + url + (note != null && !note.isBlank() ? "\n" + note : "");
        return audited.run("link_pr", issueKey + ": " + url, () -> {
            String bearer = tokenService.bearerFor(actor.personaMemberId());
            IssueResponse issue = almClient.getByKey(issueKey, bearer);
            CommentResponse comment = almClient.addComment(issue.id(), body, bearer);
            return "PR 링크 코멘트 등록 완료 (id=" + comment.id() + ")";
        });
    }

    /** 409(낙관적 락 충돌)면 재조회 후 딱 1회만 다시 시도한다. 그 외 예외(400 스킴 위반 등)는 그대로 전파한다. */
    private IssueResponse updateWithRetry(IssueResponse issue, Long assigneeId, String status, String bearer) {
        try {
            return almClient.update(issue.id(), toUpdateRequest(issue, assigneeId, status), bearer);
        } catch (AlmClient.VersionConflictException e) {
            IssueResponse fresh = almClient.getByKey(issue.key(), bearer);
            return almClient.update(fresh.id(), toUpdateRequest(fresh, assigneeId, status), bearer);
        }
    }

    /** details를 null로 보내 V2 확장 필드(parentId/sprintId/dueDate/estimateHours/resolution/fixVersionId/labels/componentIds)를 그대로 보존한다(S10). */
    private IssueUpdateRequest toUpdateRequest(IssueResponse issue, Long assigneeId, String status) {
        return new IssueUpdateRequest(
                issue.title(),
                issue.description(),
                issue.type(),
                status,
                issue.priority(),
                assigneeId,
                null,
                issue.version(),
                null);
    }

    private Long resolveAssigneeId(String assigneeSlug) {
        if (assigneeSlug == null || assigneeSlug.isBlank()) {
            return null;
        }
        return personaRepository.findBySlug(assigneeSlug)
                .map(Persona::getMemberId)
                .orElseThrow(() -> new NotFoundException("담당자 페르소나를 찾을 수 없습니다: " + assigneeSlug));
    }

    private Long resolveParentId(String parentKey, String bearer) {
        if (parentKey == null || parentKey.isBlank()) {
            return null;
        }
        return almClient.getByKey(parentKey, bearer).id();
    }

    /**
     * 일반 텍스트를 TipTap이 기대하는 최소 HTML로 감싼다(alm-backend description 계약, S10).
     * 이미 {@code <}로 시작하면(이미 HTML로 판단) 그대로 둔다. 문단은 빈 줄({@code \n\n})로
     * 나누고, 문단 내부 줄바꿈은 {@code <br/>}로 바꾼다.
     */
    static String toTipTapHtml(String text) {
        if (text == null) {
            return null;
        }
        if (text.stripLeading().startsWith("<")) {
            return text;
        }
        StringBuilder html = new StringBuilder();
        for (String paragraph : text.split("\n\n")) {
            html.append("<p>").append(paragraph.replace("\n", "<br/>")).append("</p>");
        }
        return html.toString();
    }

    private Map<String, Object> searchSummary(IssuePageResponse page) {
        List<Map<String, Object>> hits = page.items().stream()
                .map(issue -> {
                    Map<String, Object> hit = new LinkedHashMap<>();
                    hit.put("key", issue.key());
                    hit.put("title", issue.title());
                    hit.put("status", issue.status());
                    hit.put("priority", issue.priority());
                    hit.put("assigneeId", issue.assigneeId());
                    return hit;
                })
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", page.total());
        result.put("page", page.page());
        result.put("hits", hits);
        return result;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("도구 응답 직렬화 실패", e);
        }
    }
}
