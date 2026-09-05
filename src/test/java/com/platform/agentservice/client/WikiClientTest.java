package com.platform.agentservice.client;

import com.platform.agentservice.client.dto.PageCreateRequest;
import com.platform.agentservice.client.dto.PageNode;
import com.platform.agentservice.client.dto.PageResponse;
import com.platform.agentservice.client.dto.PageUpdateRequest;
import com.platform.agentservice.client.dto.SpaceResponse;
import com.platform.common.error.ConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WikiClientTest {

    private static final String BEARER = "Bearer persona-token";

    private RestClient.Builder builder() {
        return RestClient.builder().baseUrl("http://wiki-backend");
    }

    @Test
    void listSpaces_sends_correct_path_and_header() {
        RestClient.Builder builder = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WikiClient client = new WikiClient(builder.build());

        server.expect(requestTo("http://wiki-backend/api/wiki/spaces"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BEARER))
                .andRespond(withSuccess(
                        "[{\"id\":1,\"key\":\"ENG\",\"name\":\"엔지니어링\",\"description\":\"d\",\"ownerId\":null}]",
                        MediaType.APPLICATION_JSON));

        List<SpaceResponse> spaces = client.listSpaces(BEARER);

        assertThat(spaces).hasSize(1);
        assertThat(spaces.get(0).key()).isEqualTo("ENG");
        server.verify();
    }

    @Test
    void getPage_sends_correct_path_and_header() {
        RestClient.Builder builder = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WikiClient client = new WikiClient(builder.build());

        server.expect(requestTo("http://wiki-backend/api/wiki/pages/7"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BEARER))
                .andRespond(withSuccess(samplePageJson(7L, 1L, null, "제목", "본문", 3, "page", "published"),
                        MediaType.APPLICATION_JSON));

        PageResponse page = client.getPage(7L, BEARER);

        assertThat(page.id()).isEqualTo(7L);
        assertThat(page.title()).isEqualTo("제목");
        assertThat(page.content()).isEqualTo("본문");
        assertThat(page.version()).isEqualTo(3);
        server.verify();
    }

    @Test
    void searchPages_sends_query_param_and_header() {
        RestClient.Builder builder = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WikiClient client = new WikiClient(builder.build());

        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://wiki-backend/api/wiki/spaces/1/pages/search")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("q", "incident"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BEARER))
                .andRespond(withSuccess(
                        "[{\"id\":1,\"parentId\":null,\"title\":\"장애 대응\",\"type\":\"page\",\"status\":\"published\",\"position\":0,\"icon\":null,\"updatedBy\":1,\"updatedAt\":\"2026-09-01T00:00:00Z\",\"childCount\":0}]",
                        MediaType.APPLICATION_JSON));

        List<PageNode> nodes = client.searchPages(1L, "incident", BEARER);

        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).title()).isEqualTo("장애 대응");
        server.verify();
    }

    @Test
    void rootPages_sends_children_path_without_parentId_param() {
        RestClient.Builder builder = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WikiClient client = new WikiClient(builder.build());

        server.expect(requestTo("http://wiki-backend/api/wiki/spaces/1/pages/children"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BEARER))
                .andRespond(withSuccess(
                        "[{\"id\":2,\"parentId\":null,\"title\":\"루트 문서\",\"type\":\"folder\",\"status\":\"published\",\"position\":0,\"icon\":null,\"updatedBy\":1,\"updatedAt\":\"2026-09-01T00:00:00Z\",\"childCount\":2}]",
                        MediaType.APPLICATION_JSON));

        List<PageNode> nodes = client.rootPages(1L, BEARER);

        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).type()).isEqualTo("folder");
        server.verify();
    }

    @Test
    void createPage_sends_exact_body_to_pages_path() {
        RestClient.Builder builder = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WikiClient client = new WikiClient(builder.build());

        PageCreateRequest request = new PageCreateRequest(1L, null, "새 문서", "# 본문", null, "published");

        server.expect(requestTo("http://wiki-backend/api/wiki/pages"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"spaceId":1,"parentId":null,"title":"새 문서","content":"# 본문","type":null,"status":"published"}
                        """, false))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(samplePageJson(42L, 1L, null, "새 문서", "# 본문", 0, "page", "published")));

        PageResponse created = client.createPage(request, BEARER);

        assertThat(created.id()).isEqualTo(42L);
        assertThat(created.title()).isEqualTo("새 문서");
        server.verify();
    }

    @Test
    void updatePage_sends_full_replace_body() {
        RestClient.Builder builder = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WikiClient client = new WikiClient(builder.build());

        PageUpdateRequest request = new PageUpdateRequest("제목", "새 본문", 5L, 3, "본문 수정");

        server.expect(requestTo("http://wiki-backend/api/wiki/pages/7"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(content().json("""
                        {"title":"제목","content":"새 본문","parentId":5,"expectedVersion":3,"changeNote":"본문 수정"}
                        """, false))
                .andRespond(withSuccess(samplePageJson(7L, 1L, 5L, "제목", "새 본문", 4, "page", "published"),
                        MediaType.APPLICATION_JSON));

        PageResponse updated = client.updatePage(7L, request, BEARER);

        assertThat(updated.version()).isEqualTo(4);
        assertThat(updated.content()).isEqualTo("새 본문");
        server.verify();
    }

    @Test
    void updatePage_maps_409_to_version_conflict_exception() {
        RestClient.Builder builder = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WikiClient client = new WikiClient(builder.build());

        PageUpdateRequest request = new PageUpdateRequest("제목", "새 본문", null, 3, null);

        server.expect(requestTo("http://wiki-backend/api/wiki/pages/7"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"버전 충돌 — 현재 4, 요청 3\"}"));

        assertThatThrownBy(() -> client.updatePage(7L, request, BEARER))
                .isInstanceOf(WikiClient.VersionConflictException.class)
                .hasMessage("버전 충돌 — 현재 4, 요청 3");

        server.verify();
    }

    @Test
    void updatePage_passes_through_400_reparent_violation_message_as_conflict_exception() {
        RestClient.Builder builder = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WikiClient client = new WikiClient(builder.build());

        PageUpdateRequest request = new PageUpdateRequest("제목", "새 본문", 99L, 3, null);

        server.expect(requestTo("http://wiki-backend/api/wiki/pages/7"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"부모 문서를 바꿀 수 없습니다 — move API를 사용하세요\"}"));

        assertThatThrownBy(() -> client.updatePage(7L, request, BEARER))
                .isInstanceOf(ConflictException.class)
                .hasMessage("부모 문서를 바꿀 수 없습니다 — move API를 사용하세요");

        server.verify();
    }

    private static String samplePageJson(long id, long spaceId, Long parentId, String title, String content,
                                          int version, String type, String status) {
        return """
                {"id":%d,"spaceId":%d,"parentId":%s,"title":"%s","content":"%s","version":%d,
                 "type":"%s","status":"%s","position":0,"icon":null,"views":0,"archivedAt":null,
                 "ownerId":null,"verifiedAt":null,"verifiedBy":null,"verifiedUntil":null,
                 "importedAuthorName":null,"importedSourceUrl":null}
                """.formatted(id, spaceId, parentId == null ? "null" : parentId, title, content, version, type, status);
    }
}
