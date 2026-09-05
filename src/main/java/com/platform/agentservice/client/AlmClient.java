package com.platform.agentservice.client;

import com.platform.agentservice.client.dto.CommentRequest;
import com.platform.agentservice.client.dto.CommentResponse;
import com.platform.agentservice.client.dto.IssueCreateRequest;
import com.platform.agentservice.client.dto.IssuePageResponse;
import com.platform.agentservice.client.dto.IssueResponse;
import com.platform.agentservice.client.dto.IssueUpdateRequest;
import com.platform.agentservice.client.dto.WorklogRequest;
import com.platform.agentservice.client.dto.WorklogResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * alm-backend({@code /api/alm/**}) 호출 전용 클라이언트(S10). 모든 메서드는 호출자가
 * 넘긴 {@code bearer}("Bearer &lt;토큰&gt;" 형태 — 항상 {@code TokenService.bearerFor}가
 * 발급한 페르소나 서비스 토큰)를 그대로 Authorization 헤더에 싣는다 — 이 클라이언트
 * 자신은 누구의 토큰인지 모른다, 호출자(IssueTools)가 매번 넘긴다. 오류는
 * {@link DownstreamErrors}로 매핑하되, {@code update}만은 409(낙관적 락 충돌)를
 * {@link VersionConflictException}으로 따로 던져 호출자가 "재조회 후 1회 재시도"를
 * 판단할 수 있게 한다.
 */
@Component
public class AlmClient {

    private static final ParameterizedTypeReference<List<CommentResponse>> COMMENT_LIST =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<WorklogResponse>> WORKLOG_LIST =
            new ParameterizedTypeReference<>() {};

    private final RestClient almRestClient;

    public AlmClient(@Qualifier("almRestClient") RestClient almRestClient) {
        this.almRestClient = almRestClient;
    }

    /** 409 낙관적 락 충돌 — {@link #update}에서만 던진다. 다른 4xx/5xx는 {@link DownstreamErrors}로 매핑된 예외를 그대로 던진다. */
    public static final class VersionConflictException extends RuntimeException {
        public VersionConflictException(String message) {
            super(message);
        }
    }

    /** {@code GET /api/alm/issues/search}. 목록형 파라미터는 콤마로 합쳐 단일 값으로 보낸다(alm-backend가 콤마 분리 바인딩을 지원, S10). */
    public IssuePageResponse search(Long projectId, String text, List<String> statuses, String assignee,
                                     List<String> labels, Integer page, String bearer) {
        try {
            return almRestClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/api/alm/issues/search");
                        if (projectId != null) {
                            uriBuilder.queryParam("projectIds", projectId);
                        }
                        if (text != null && !text.isBlank()) {
                            uriBuilder.queryParam("text", text);
                        }
                        if (statuses != null && !statuses.isEmpty()) {
                            uriBuilder.queryParam("statuses", String.join(",", statuses));
                        }
                        if (assignee != null && !assignee.isBlank()) {
                            uriBuilder.queryParam("assignees", assignee);
                        }
                        if (labels != null && !labels.isEmpty()) {
                            uriBuilder.queryParam("labels", String.join(",", labels));
                        }
                        if (page != null) {
                            uriBuilder.queryParam("page", page);
                        }
                        return uriBuilder.build();
                    })
                    .header(HttpHeaders.AUTHORIZATION, bearer)
                    .retrieve()
                    .body(IssuePageResponse.class);
        } catch (RestClientException e) {
            throw DownstreamErrors.map(e, "이슈 검색");
        }
    }

    /** {@code GET /api/alm/issues/by-key/{key}}. */
    public IssueResponse getByKey(String key, String bearer) {
        try {
            return almRestClient.get()
                    .uri("/api/alm/issues/by-key/{key}", key)
                    .header(HttpHeaders.AUTHORIZATION, bearer)
                    .retrieve()
                    .body(IssueResponse.class);
        } catch (RestClientException e) {
            throw DownstreamErrors.map(e, "이슈 조회");
        }
    }

    /** {@code GET /api/alm/issues/{id}/comments} — 생성순 오름차순(오래된 것부터). */
    public List<CommentResponse> comments(long issueId, String bearer) {
        try {
            return almRestClient.get()
                    .uri("/api/alm/issues/{id}/comments", issueId)
                    .header(HttpHeaders.AUTHORIZATION, bearer)
                    .retrieve()
                    .body(COMMENT_LIST);
        } catch (RestClientException e) {
            throw DownstreamErrors.map(e, "코멘트 조회");
        }
    }

    /** {@code GET /api/alm/issues/{id}/worklogs} — 작업일 오름차순. */
    public List<WorklogResponse> worklogs(long issueId, String bearer) {
        try {
            return almRestClient.get()
                    .uri("/api/alm/issues/{id}/worklogs", issueId)
                    .header(HttpHeaders.AUTHORIZATION, bearer)
                    .retrieve()
                    .body(WORKLOG_LIST);
        } catch (RestClientException e) {
            throw DownstreamErrors.map(e, "워크로그 조회");
        }
    }

    /** {@code POST /api/alm/projects/{projectId}/issues}. */
    public IssueResponse create(long projectId, IssueCreateRequest request, String bearer) {
        try {
            return almRestClient.post()
                    .uri("/api/alm/projects/{projectId}/issues", projectId)
                    .header(HttpHeaders.AUTHORIZATION, bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(IssueResponse.class);
        } catch (RestClientException e) {
            throw DownstreamErrors.map(e, "이슈 생성");
        }
    }

    /**
     * {@code PUT /api/alm/issues/{issueId}} — full replace. 409는 낙관적 락 충돌이므로
     * {@link VersionConflictException}으로 따로 던진다(호출자가 재조회 후 1회 재시도할지
     * 판단). 그 외 오류는 {@link DownstreamErrors}가 매핑한 예외(400 스킴 위반이면
     * {@code ConflictException}에 원본 메시지가 그대로 담긴다)를 던진다.
     */
    public IssueResponse update(long issueId, IssueUpdateRequest request, String bearer) {
        try {
            return almRestClient.put()
                    .uri("/api/alm/issues/{id}", issueId)
                    .header(HttpHeaders.AUTHORIZATION, bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(IssueResponse.class);
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() == 409) {
                throw new VersionConflictException(DownstreamErrors.extractMessage(e, "이슈 수정"));
            }
            throw DownstreamErrors.map(e, "이슈 수정");
        } catch (RestClientException e) {
            throw DownstreamErrors.map(e, "이슈 수정");
        }
    }

    /** {@code POST /api/alm/issues/{id}/comments} — VIEW 권한으로 가능. */
    public CommentResponse addComment(long issueId, String body, String bearer) {
        try {
            return almRestClient.post()
                    .uri("/api/alm/issues/{id}/comments", issueId)
                    .header(HttpHeaders.AUTHORIZATION, bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new CommentRequest(body, null))
                    .retrieve()
                    .body(CommentResponse.class);
        } catch (RestClientException e) {
            throw DownstreamErrors.map(e, "코멘트 작성");
        }
    }

    /** {@code POST /api/alm/issues/{id}/worklogs} — EDIT 권한 필요. */
    public WorklogResponse addWorklog(long issueId, WorklogRequest request, String bearer) {
        try {
            return almRestClient.post()
                    .uri("/api/alm/issues/{id}/worklogs", issueId)
                    .header(HttpHeaders.AUTHORIZATION, bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(WorklogResponse.class);
        } catch (RestClientException e) {
            throw DownstreamErrors.map(e, "워크로그 기록");
        }
    }
}
