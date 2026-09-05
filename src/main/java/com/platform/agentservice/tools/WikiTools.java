package com.platform.agentservice.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agentservice.client.TokenService;
import com.platform.agentservice.client.WikiClient;
import com.platform.agentservice.client.dto.PageCreateRequest;
import com.platform.agentservice.client.dto.PageNode;
import com.platform.agentservice.client.dto.PageResponse;
import com.platform.agentservice.client.dto.PageUpdateRequest;
import com.platform.agentservice.client.dto.SpaceResponse;
import com.platform.agentservice.pat.PatPrincipal;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Wiki 문서 도구 6종(S10). 전부 페르소나 서비스 토큰({@link TokenService#bearerFor})으로
 * wiki-backend를 호출하고, {@link Audited}로 감사 로그를 남긴다. {@link IssueTools}와
 * 동일 패턴 — 각 메서드는 가장 먼저 {@link ToolActor#current()}로 호출자를 확인한다.
 *
 * <p><b>재부모화 미지원(S10)</b>: wiki-backend {@code PUT /api/wiki/pages/{id}}는
 * {@code parentId}가 현재 값과 다르면 400을 던진다(부모 변경은 전용 move API 몫). 그래서
 * {@code update_page}/{@code append_to_page}는 항상 재조회한 현재 {@code parentId}를
 * 그대로 채워 보낸다 — 이 두 도구로는 페이지를 다른 위치로 옮길 수 없다(P1 잠정).
 *
 * <p><b>409 재시도(S10)</b>: {@code update_page}/{@code append_to_page}는 낙관적 락
 * 충돌({@link WikiClient.VersionConflictException}) 발생 시 재조회 후 딱 1회만 재시도한다
 * ({@link IssueTools}의 {@code claim_issue}와 동일 정책). 재시도 시 {@code parentId}·
 * {@code expectedVersion}·(제목 미지정 시)제목·(append의) 본문 베이스는 전부 재조회한
 * 최신 스냅샷에서 다시 계산한다 — 그 사이 다른 편집을 덮어쓰지 않기 위해서다.
 */
@Component
public class WikiTools {

    private final WikiClient wikiClient;
    private final TokenService tokenService;
    private final Audited audited;
    private final ObjectMapper objectMapper;

    public WikiTools(WikiClient wikiClient, TokenService tokenService, Audited audited, ObjectMapper objectMapper) {
        this.wikiClient = wikiClient;
        this.tokenService = tokenService;
        this.audited = audited;
        this.objectMapper = objectMapper;
    }

    @McpTool(name = "list_spaces", description = "현재 페르소나가 접근 가능한 Wiki 스페이스 목록을 조회한다.")
    public String listSpaces() {
        PatPrincipal actor = ToolActor.current();
        return audited.run("list_spaces", "list_spaces", () -> {
            String bearer = tokenService.bearerFor(actor.personaMemberId());
            List<SpaceResponse> spaces = wikiClient.listSpaces(bearer);
            List<Map<String, Object>> compact = spaces.stream()
                    .map(space -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", space.id());
                        row.put("key", space.key());
                        row.put("name", space.name());
                        return row;
                    })
                    .toList();
            return writeJson(compact);
        });
    }

    @McpTool(name = "get_page", description = "Wiki 문서 하나를 제목·마크다운 본문·버전과 함께 조회한다.")
    public String getPage(@McpToolParam(description = "문서 id", required = true) long pageId) {
        PatPrincipal actor = ToolActor.current();
        return audited.run("get_page", "pageId=" + pageId, () -> {
            String bearer = tokenService.bearerFor(actor.personaMemberId());
            PageResponse page = wikiClient.getPage(pageId, bearer);
            return writeJson(pageDetails(page));
        });
    }

    @McpTool(name = "find_pages", description = "스페이스 안에서 문서를 찾는다 — query가 있으면 제목 검색, 없으면 루트 문서 목록.")
    public String findPages(
            @McpToolParam(description = "스페이스 id", required = true) long spaceId,
            @McpToolParam(description = "제목 검색어(생략 시 루트 문서 목록)", required = false) String query) {
        PatPrincipal actor = ToolActor.current();
        String summary = "spaceId=" + spaceId + " query=" + query;
        return audited.run("find_pages", summary, () -> {
            String bearer = tokenService.bearerFor(actor.personaMemberId());
            List<PageNode> nodes = (query != null && !query.isBlank())
                    ? wikiClient.searchPages(spaceId, query, bearer)
                    : wikiClient.rootPages(spaceId, bearer);
            List<Map<String, Object>> compact = nodes.stream()
                    .map(node -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", node.id());
                        row.put("title", node.title());
                        row.put("type", node.type());
                        row.put("status", node.status());
                        return row;
                    })
                    .toList();
            return writeJson(compact);
        });
    }

    @McpTool(name = "create_page", description = "Wiki 스페이스에 새 문서를 만든다(작성자는 항상 현재 페르소나).")
    public String createPage(
            @McpToolParam(description = "스페이스 id", required = true) long spaceId,
            @McpToolParam(description = "상위 문서 id(생략 시 루트)", required = false) Long parentId,
            @McpToolParam(description = "문서 제목", required = true) String title,
            @McpToolParam(description = "본문(마크다운 원문)", required = true) String contentMarkdown,
            @McpToolParam(description = "true면 초안(draft)으로 생성, 생략/false면 즉시 게시(published)", required = false) Boolean draft) {
        PatPrincipal actor = ToolActor.current();
        String summary = "spaceId=" + spaceId + " title=" + title;
        return audited.run("create_page", summary, () -> {
            String bearer = tokenService.bearerFor(actor.personaMemberId());
            String status = Boolean.TRUE.equals(draft) ? "draft" : "published";
            PageCreateRequest request = new PageCreateRequest(spaceId, parentId, title, contentMarkdown, null, status);
            PageResponse created = wikiClient.createPage(request, bearer);
            return "문서 생성 완료 (id=" + created.id() + ", title=" + created.title() + ")";
        });
    }

    @McpTool(name = "update_page", description = "Wiki 문서 본문/제목을 수정한다(재부모화 미지원 — 부모는 항상 현재 값 유지).")
    public String updatePage(
            @McpToolParam(description = "문서 id", required = true) long pageId,
            @McpToolParam(description = "새 제목(생략 시 현재 제목 유지)", required = false) String title,
            @McpToolParam(description = "새 본문(마크다운 원문) — 기존 본문 전체를 대체한다", required = true) String contentMarkdown,
            @McpToolParam(description = "변경 요약(선택)", required = false) String changeNote) {
        PatPrincipal actor = ToolActor.current();
        String summary = "pageId=" + pageId + " title=" + title;
        return audited.run("update_page", summary, () -> {
            String bearer = tokenService.bearerFor(actor.personaMemberId());
            PageResponse current = wikiClient.getPage(pageId, bearer);
            Function<PageResponse, String> titleOf = snapshot ->
                    (title != null && !title.isBlank()) ? title : snapshot.title();
            Function<PageResponse, String> contentOf = snapshot -> contentMarkdown;
            PageResponse updated = updateWithRetry(pageId, current, titleOf, contentOf, changeNote, bearer);
            return "문서 수정 완료 (id=" + updated.id() + ", version=" + updated.version() + ")";
        });
    }

    @McpTool(name = "append_to_page", description = "Wiki 문서 끝에 내용을 덧붙인다(기존 본문 보존) — 작업기록 누적용.")
    public String appendToPage(
            @McpToolParam(description = "문서 id", required = true) long pageId,
            @McpToolParam(description = "덧붙일 본문(마크다운 원문)", required = true) String sectionMarkdown,
            @McpToolParam(description = "변경 요약(선택)", required = false) String changeNote) {
        PatPrincipal actor = ToolActor.current();
        return audited.run("append_to_page", "pageId=" + pageId, () -> {
            String bearer = tokenService.bearerFor(actor.personaMemberId());
            PageResponse current = wikiClient.getPage(pageId, bearer);
            Function<PageResponse, String> titleOf = PageResponse::title;
            Function<PageResponse, String> contentOf = snapshot -> snapshot.content() + "\n\n" + sectionMarkdown;
            PageResponse updated = updateWithRetry(pageId, current, titleOf, contentOf, changeNote, bearer);
            return "문서에 내용을 추가했습니다 (id=" + updated.id() + ", version=" + updated.version() + ")";
        });
    }

    /**
     * 409(낙관적 락 충돌)면 재조회 후 딱 1회만 다시 시도한다. {@code parentId}·
     * {@code expectedVersion}은 항상 그 시점 스냅샷 값을 쓰고, {@code title}/{@code content}도
     * 재시도 시에는 최신 스냅샷을 기준으로 다시 계산한다(동시 편집을 덮어쓰지 않기 위해).
     * 그 외 예외(400 재부모화 위반 등)는 그대로 전파한다.
     */
    private PageResponse updateWithRetry(long pageId, PageResponse snapshot,
                                          Function<PageResponse, String> titleOf,
                                          Function<PageResponse, String> contentOf,
                                          String changeNote, String bearer) {
        PageUpdateRequest request = new PageUpdateRequest(
                titleOf.apply(snapshot), contentOf.apply(snapshot), snapshot.parentId(), snapshot.version(), changeNote);
        try {
            return wikiClient.updatePage(pageId, request, bearer);
        } catch (WikiClient.VersionConflictException e) {
            PageResponse fresh = wikiClient.getPage(pageId, bearer);
            PageUpdateRequest retry = new PageUpdateRequest(
                    titleOf.apply(fresh), contentOf.apply(fresh), fresh.parentId(), fresh.version(), changeNote);
            return wikiClient.updatePage(pageId, retry, bearer);
        }
    }

    private Map<String, Object> pageDetails(PageResponse page) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", page.id());
        result.put("spaceId", page.spaceId());
        result.put("title", page.title());
        result.put("content", page.content());
        result.put("version", page.version());
        result.put("status", page.status());
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
