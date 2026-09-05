package com.platform.agentservice.client;

import com.platform.agentservice.client.dto.PageCreateRequest;
import com.platform.agentservice.client.dto.PageNode;
import com.platform.agentservice.client.dto.PageResponse;
import com.platform.agentservice.client.dto.PageUpdateRequest;
import com.platform.agentservice.client.dto.SpaceResponse;
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
 * wiki-backend({@code /api/wiki/**}) 호출 전용 클라이언트(S10). {@link AlmClient}와 동일한
 * 패턴 — 모든 메서드는 호출자가 넘긴 {@code bearer}("Bearer &lt;토큰&gt;", 항상
 * {@code TokenService.bearerFor}가 발급한 페르소나 서비스 토큰)를 그대로 Authorization
 * 헤더에 싣는다. 오류는 {@link DownstreamErrors}로 매핑하되, {@code updatePage}만은
 * 409(낙관적 락 충돌)를 {@link VersionConflictException}으로 따로 던져 호출자(WikiTools)가
 * "재조회 후 1회 재시도"를 판단할 수 있게 한다.
 */
@Component
public class WikiClient {

    private static final ParameterizedTypeReference<List<SpaceResponse>> SPACE_LIST =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<PageNode>> PAGE_NODE_LIST =
            new ParameterizedTypeReference<>() {};

    private final RestClient wikiRestClient;

    public WikiClient(@Qualifier("wikiRestClient") RestClient wikiRestClient) {
        this.wikiRestClient = wikiRestClient;
    }

    /** 409 낙관적 락 충돌 — {@link #updatePage}에서만 던진다. 다른 4xx/5xx는 {@link DownstreamErrors}로 매핑된 예외를 그대로 던진다. */
    public static final class VersionConflictException extends RuntimeException {
        public VersionConflictException(String message) {
            super(message);
        }
    }

    /** {@code GET /api/wiki/spaces} — 페르소나가 접근 가능한 스페이스만 서버가 걸러서 돌려준다. */
    public List<SpaceResponse> listSpaces(String bearer) {
        try {
            return wikiRestClient.get()
                    .uri("/api/wiki/spaces")
                    .header(HttpHeaders.AUTHORIZATION, bearer)
                    .retrieve()
                    .body(SPACE_LIST);
        } catch (RestClientException e) {
            throw DownstreamErrors.map(e, "스페이스 목록 조회");
        }
    }

    /** {@code GET /api/wiki/pages/{id}}. */
    public PageResponse getPage(long pageId, String bearer) {
        try {
            return wikiRestClient.get()
                    .uri("/api/wiki/pages/{id}", pageId)
                    .header(HttpHeaders.AUTHORIZATION, bearer)
                    .retrieve()
                    .body(PageResponse.class);
        } catch (RestClientException e) {
            throw DownstreamErrors.map(e, "문서 조회");
        }
    }

    /** {@code GET /api/wiki/spaces/{spaceId}/pages/search?q=} — 제목 검색. */
    public List<PageNode> searchPages(long spaceId, String query, String bearer) {
        try {
            return wikiRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/wiki/spaces/{spaceId}/pages/search")
                            .queryParam("q", query)
                            .build(spaceId))
                    .header(HttpHeaders.AUTHORIZATION, bearer)
                    .retrieve()
                    .body(PAGE_NODE_LIST);
        } catch (RestClientException e) {
            throw DownstreamErrors.map(e, "문서 제목 검색");
        }
    }

    /** {@code GET /api/wiki/spaces/{spaceId}/pages/children} — parentId 생략 = 루트 목록. */
    public List<PageNode> rootPages(long spaceId, String bearer) {
        try {
            return wikiRestClient.get()
                    .uri("/api/wiki/spaces/{spaceId}/pages/children", spaceId)
                    .header(HttpHeaders.AUTHORIZATION, bearer)
                    .retrieve()
                    .body(PAGE_NODE_LIST);
        } catch (RestClientException e) {
            throw DownstreamErrors.map(e, "루트 문서 목록 조회");
        }
    }

    /** {@code POST /api/wiki/pages}. */
    public PageResponse createPage(PageCreateRequest request, String bearer) {
        try {
            return wikiRestClient.post()
                    .uri("/api/wiki/pages")
                    .header(HttpHeaders.AUTHORIZATION, bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(PageResponse.class);
        } catch (RestClientException e) {
            throw DownstreamErrors.map(e, "문서 생성");
        }
    }

    /**
     * {@code PUT /api/wiki/pages/{id}} — full replace. 409는 낙관적 락 충돌이므로
     * {@link VersionConflictException}으로 따로 던진다(호출자가 재조회 후 1회 재시도할지
     * 판단). {@code parentId}가 현재 값과 다르면 wiki-backend가 400을 던지며, 그 메시지는
     * {@link DownstreamErrors}가 매핑한 {@code ConflictException}에 그대로 담긴다.
     */
    public PageResponse updatePage(long pageId, PageUpdateRequest request, String bearer) {
        try {
            return wikiRestClient.put()
                    .uri("/api/wiki/pages/{id}", pageId)
                    .header(HttpHeaders.AUTHORIZATION, bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(PageResponse.class);
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() == 409) {
                throw new VersionConflictException(DownstreamErrors.extractMessage(e, "문서 수정"));
            }
            throw DownstreamErrors.map(e, "문서 수정");
        } catch (RestClientException e) {
            throw DownstreamErrors.map(e, "문서 수정");
        }
    }
}
