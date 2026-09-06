package com.platform.agentservice.client;

import com.platform.agentservice.client.dto.CommentResponse;
import com.platform.agentservice.client.dto.IssueCreateRequest;
import com.platform.agentservice.client.dto.IssueDetailsRequest;
import com.platform.agentservice.client.dto.IssuePageResponse;
import com.platform.agentservice.client.dto.IssueResponse;
import com.platform.agentservice.client.dto.IssueUpdateRequest;
import com.platform.agentservice.client.dto.ProjectResponse;
import com.platform.agentservice.client.dto.ProjectSettingsResponse;
import com.platform.agentservice.client.dto.WebLinkResponse;
import com.platform.agentservice.client.dto.WorklogRequest;
import com.platform.agentservice.client.dto.WorklogResponse;
import com.platform.common.error.ConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
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

class AlmClientTest {

    private static final String BEARER = "Bearer persona-token";

    private RestClient.Builder builder() {
        return RestClient.builder().baseUrl("http://alm-backend");
    }

    @Test
    void search_sends_exact_query_params_and_authorization_header() {
        RestClient.Builder builder = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AlmClient client = new AlmClient(builder.build());

        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://alm-backend/api/alm/issues/search")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("projectIds", "5"))
                .andExpect(queryParam("text", "loginbug"))
                .andExpect(queryParam("statuses", "todo,inprogress"))
                .andExpect(queryParam("assignees", "unassigned"))
                .andExpect(queryParam("labels", "urgent,backend"))
                .andExpect(queryParam("page", "2"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BEARER))
                .andRespond(withSuccess(
                        "{\"items\":[],\"page\":2,\"size\":50,\"total\":0}", MediaType.APPLICATION_JSON));

        IssuePageResponse result = client.search(5L, "loginbug", List.of("todo", "inprogress"),
                "unassigned", List.of("urgent", "backend"), 2, BEARER);

        assertThat(result.total()).isZero();
        assertThat(result.page()).isEqualTo(2);
        server.verify();
    }

    @Test
    void search_omits_absent_optional_params() {
        RestClient.Builder builder = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AlmClient client = new AlmClient(builder.build());

        server.expect(requestTo("http://alm-backend/api/alm/issues/search"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BEARER))
                .andRespond(withSuccess("{\"items\":[],\"page\":0,\"size\":50,\"total\":0}", MediaType.APPLICATION_JSON));

        client.search(null, null, null, null, null, null, BEARER);

        server.verify();
    }

    @Test
    void getByKey_sends_correct_path_and_header() {
        RestClient.Builder builder = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AlmClient client = new AlmClient(builder.build());

        server.expect(requestTo("http://alm-backend/api/alm/issues/by-key/PROJ-7"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BEARER))
                .andRespond(withSuccess(sampleIssueJson(7L, "PROJ-7", 3), MediaType.APPLICATION_JSON));

        IssueResponse issue = client.getByKey("PROJ-7", BEARER);

        assertThat(issue.id()).isEqualTo(7L);
        assertThat(issue.key()).isEqualTo("PROJ-7");
        server.verify();
    }

    @Test
    void comments_sends_correct_path_and_header() {
        RestClient.Builder builder = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AlmClient client = new AlmClient(builder.build());

        server.expect(requestTo("http://alm-backend/api/alm/issues/7/comments"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BEARER))
                .andRespond(withSuccess(
                        "[{\"id\":1,\"issueId\":7,\"authorId\":10,\"body\":\"hi\",\"createdAt\":\"2026-09-01T00:00:00Z\",\"updatedAt\":\"2026-09-01T00:00:00Z\"}]",
                        MediaType.APPLICATION_JSON));

        List<CommentResponse> comments = client.comments(7L, BEARER);

        assertThat(comments).hasSize(1);
        assertThat(comments.get(0).body()).isEqualTo("hi");
        server.verify();
    }

    @Test
    void worklogs_sends_correct_path_and_header() {
        RestClient.Builder builder = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AlmClient client = new AlmClient(builder.build());

        server.expect(requestTo("http://alm-backend/api/alm/issues/7/worklogs"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BEARER))
                .andRespond(withSuccess(
                        "[{\"id\":1,\"issueId\":7,\"authorId\":10,\"hours\":2.5,\"comment\":\"c\",\"workedOn\":\"2026-09-01\",\"createdAt\":\"2026-09-01T00:00:00Z\"}]",
                        MediaType.APPLICATION_JSON));

        List<WorklogResponse> worklogs = client.worklogs(7L, BEARER);

        assertThat(worklogs).hasSize(1);
        assertThat(worklogs.get(0).hours()).isEqualByComparingTo("2.5");
        server.verify();
    }

    @Test
    void listProjects_sends_correct_path_and_header() {
        RestClient.Builder builder = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AlmClient client = new AlmClient(builder.build());

        server.expect(requestTo("http://alm-backend/api/alm/projects"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BEARER))
                .andRespond(withSuccess(
                        "[{\"id\":9,\"key\":\"PROJ\",\"name\":\"프로젝트\",\"description\":\"d\",\"version\":1}]",
                        MediaType.APPLICATION_JSON));

        List<ProjectResponse> projects = client.listProjects(BEARER);

        assertThat(projects).hasSize(1);
        assertThat(projects.get(0).key()).isEqualTo("PROJ");
        assertThat(projects.get(0).name()).isEqualTo("프로젝트");
        server.verify();
    }

    @Test
    void getProject_sends_correct_path_and_header() {
        RestClient.Builder builder = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AlmClient client = new AlmClient(builder.build());

        server.expect(requestTo("http://alm-backend/api/alm/projects/9"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BEARER))
                .andRespond(withSuccess(
                        "{\"id\":9,\"key\":\"PROJ\",\"name\":\"프로젝트\"}", MediaType.APPLICATION_JSON));

        ProjectResponse project = client.getProject(9L, BEARER);

        assertThat(project.id()).isEqualTo(9L);
        assertThat(project.key()).isEqualTo("PROJ");
        server.verify();
    }

    @Test
    void getProjectSettings_sends_correct_path_and_header_and_parses_body() {
        RestClient.Builder builder = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AlmClient client = new AlmClient(builder.build());

        server.expect(requestTo("http://alm-backend/api/alm/projects/9/settings"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BEARER))
                .andRespond(withSuccess("""
                        {"body":{
                          "statuses":[{"id":"todo"},{"id":"inprogress"},{"id":"done"}],
                          "transitions":[],
                          "enabledTypes":["task","bug"],
                          "enabledPriorities":["highest","high","medium","low","lowest"],
                          "defaultPriority":"medium",
                          "fields":[{"id":"assignee","visible":true,"required":true}],
                          "fieldsByType":{}
                        },
                        "source":"scheme",
                        "scheme":{"id":"default","name":"기본","isDefault":true,"body":{}}}
                        """, MediaType.APPLICATION_JSON));

        ProjectSettingsResponse settings = client.getProjectSettings(9L, BEARER);

        assertThat(settings.body().statuses()).extracting(ProjectSettingsResponse.StatusEntry::id)
                .containsExactly("todo", "inprogress", "done");
        assertThat(settings.body().enabledTypes()).containsExactly("task", "bug");
        assertThat(settings.body().defaultPriority()).isEqualTo("medium");
        assertThat(settings.body().fields()).extracting(ProjectSettingsResponse.FieldConfigEntry::id)
                .containsExactly("assignee");
        server.verify();
    }

    @Test
    void create_sends_exact_body_to_project_issues_path() {
        RestClient.Builder builder = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AlmClient client = new AlmClient(builder.build());

        IssueCreateRequest request = new IssueCreateRequest(
                "제목", "<p>설명</p>", "task", null, "high", 42L,
                new IssueDetailsRequest(null, null, null, null, null, null, List.of("bug"), null), null);

        server.expect(requestTo("http://alm-backend/api/alm/projects/9/issues"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"title":"제목","description":"<p>설명</p>","type":"task","status":null,"priority":"high",
                         "assigneeId":42,"details":{"labels":["bug"]},"mentionedUserIds":null}
                        """, false))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(sampleIssueJson(99L, "PROJ-99", 0)));

        IssueResponse created = client.create(9L, request, BEARER);

        assertThat(created.key()).isEqualTo("PROJ-99");
        server.verify();
    }

    @Test
    void update_sends_full_replace_body_including_details() {
        RestClient.Builder builder = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AlmClient client = new AlmClient(builder.build());

        IssueUpdateRequest request = new IssueUpdateRequest(
                "제목", "<p>설명</p>", "task", "inprogress", "high", 42L, null, 3, null);

        server.expect(requestTo("http://alm-backend/api/alm/issues/7"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(content().json("""
                        {"title":"제목","description":"<p>설명</p>","type":"task","status":"inprogress",
                         "priority":"high","assigneeId":42,"details":null,"expectedVersion":3,"mentionedUserIds":null}
                        """, false))
                .andRespond(withSuccess(sampleIssueJson(7L, "PROJ-7", 4), MediaType.APPLICATION_JSON));

        IssueResponse updated = client.update(7L, request, BEARER);

        assertThat(updated.version()).isEqualTo(4);
        server.verify();
    }

    @Test
    void update_maps_409_to_version_conflict_exception() {
        RestClient.Builder builder = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AlmClient client = new AlmClient(builder.build());

        IssueUpdateRequest request = new IssueUpdateRequest(
                "제목", null, "task", "inprogress", "high", 42L, null, 3, null);

        server.expect(requestTo("http://alm-backend/api/alm/issues/7"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"다른 사용자가 먼저 이슈를 수정했습니다\"}"));

        assertThatThrownBy(() -> client.update(7L, request, BEARER))
                .isInstanceOf(AlmClient.VersionConflictException.class)
                .hasMessage("다른 사용자가 먼저 이슈를 수정했습니다");

        server.verify();
    }

    @Test
    void update_passes_through_400_scheme_violation_message_as_conflict_exception() {
        RestClient.Builder builder = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AlmClient client = new AlmClient(builder.build());

        IssueUpdateRequest request = new IssueUpdateRequest(
                "제목", null, "task", "done", "high", 42L, null, 3, null);

        server.expect(requestTo("http://alm-backend/api/alm/issues/7"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"허용되지 않는 상태 전이입니다: todo -> done\"}"));

        assertThatThrownBy(() -> client.update(7L, request, BEARER))
                .isInstanceOf(ConflictException.class)
                .hasMessage("허용되지 않는 상태 전이입니다: todo -> done");

        server.verify();
    }

    @Test
    void addComment_sends_exact_body_and_path() {
        RestClient.Builder builder = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AlmClient client = new AlmClient(builder.build());

        server.expect(requestTo("http://alm-backend/api/alm/issues/7/comments"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(content().json("{\"body\":\"hello\",\"mentionedUserIds\":null}", false))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":11,\"issueId\":7,\"authorId\":42,\"body\":\"hello\",\"createdAt\":\"2026-09-01T00:00:00Z\",\"updatedAt\":\"2026-09-01T00:00:00Z\"}"));

        CommentResponse comment = client.addComment(7L, "hello", BEARER);

        assertThat(comment.id()).isEqualTo(11L);
        server.verify();
    }

    @Test
    void addWorklog_sends_exact_body_and_path() {
        RestClient.Builder builder = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AlmClient client = new AlmClient(builder.build());

        WorklogRequest request = new WorklogRequest(new BigDecimal("1.5"), "worked", LocalDate.of(2026, 9, 1));

        server.expect(requestTo("http://alm-backend/api/alm/issues/7/worklogs"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(content().json("{\"hours\":1.5,\"comment\":\"worked\",\"workedOn\":\"2026-09-01\"}", false))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":21,\"issueId\":7,\"authorId\":42,\"hours\":1.5,\"comment\":\"worked\",\"workedOn\":\"2026-09-01\",\"createdAt\":\"2026-09-01T00:00:00Z\"}"));

        WorklogResponse worklog = client.addWorklog(7L, request, BEARER);

        assertThat(worklog.id()).isEqualTo(21L);
        server.verify();
    }

    @Test
    void addWebLink_sends_exact_body_and_path_and_returns_created_link() {
        RestClient.Builder builder = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AlmClient client = new AlmClient(builder.build());

        server.expect(requestTo("http://alm-backend/api/alm/issues/7/web-links"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(content().json("{\"url\":\"https://github.com/o/r/pull/1\",\"title\":\"PR\",\"kind\":\"PR\"}", false))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":31,\"issueId\":7,\"url\":\"https://github.com/o/r/pull/1\",\"title\":\"PR\",\"kind\":\"PR\",\"createdBy\":42,\"createdAt\":\"2026-09-01T00:00:00Z\"}"));

        WebLinkResponse link = client.addWebLink(7L, "https://github.com/o/r/pull/1", "PR", "PR", BEARER);

        assertThat(link.id()).isEqualTo(31L);
        assertThat(link.kind()).isEqualTo("PR");
        server.verify();
    }

    /** 같은 issue+url이면 alm-backend가 200으로 기존 링크를 돌려준다(멱등) — 클라이언트는 상태코드와 무관하게 그대로 파싱해야 한다. */
    @Test
    void addWebLink_parses_body_when_alm_backend_returns_200_for_idempotent_existing_link() {
        RestClient.Builder builder = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AlmClient client = new AlmClient(builder.build());

        server.expect(requestTo("http://alm-backend/api/alm/issues/7/web-links"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":31,\"issueId\":7,\"url\":\"https://github.com/o/r/commit/abc\",\"title\":\"fix: x\",\"kind\":\"COMMIT\",\"createdBy\":42,\"createdAt\":\"2026-09-01T00:00:00Z\"}"));

        WebLinkResponse link = client.addWebLink(7L, "https://github.com/o/r/commit/abc", "fix: x", "COMMIT", BEARER);

        assertThat(link.id()).isEqualTo(31L);
        assertThat(link.kind()).isEqualTo("COMMIT");
        server.verify();
    }

    private static String sampleIssueJson(long id, String key, int version) {
        return """
                {"id":%d,"key":"%s","projectId":9,"title":"제목","description":"<p>설명</p>",
                 "type":"task","status":"todo","priority":"high","assigneeId":42,"reporterId":1,
                 "parentId":null,"sprintId":null,"dueDate":null,"estimateHours":null,"resolution":null,
                 "fixVersionId":null,"labels":[],"componentIds":[],"order":0,"version":%d,
                 "createdAt":"2026-09-01T00:00:00Z","updatedAt":"2026-09-01T00:00:00Z","archivedAt":null}
                """.formatted(id, key, version);
    }
}
