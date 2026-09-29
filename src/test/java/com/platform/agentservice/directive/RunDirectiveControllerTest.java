package com.platform.agentservice.directive;

import com.platform.agentservice.TestAuth;
import com.platform.agentservice.audit.ToolCallAudit;
import com.platform.agentservice.audit.ToolCallAuditRepository;
import com.platform.agentservice.authz.PermissionClient;
import com.platform.agentservice.authz.PermissionDecision;
import com.platform.agentservice.pat.PatPrincipal;
import com.platform.agentservice.run.Run;
import com.platform.agentservice.run.RunRepository;
import com.platform.agentservice.run.RunTrigger;
import com.platform.agentservice.run.RunType;
import com.platform.common.error.ServiceUnavailableException;
import com.platform.proto.org.v1.ResourceType;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실행 중 지시 API(AGP-67) 계약 + 실제 MCP 도구 목록(ToolInputGuard가 감싼 빈)을 거친 전달. 권한: POST=run 프로젝트 관리자(cancel과 같다),
 * GET=인증 사용자 누구나(본문은 관리자만).
 */
@SpringBootTest
@ActiveProfiles("test")
class RunDirectiveControllerTest {

    @Autowired WebApplicationContext context;
    @Autowired RunRepository runRepository;
    @Autowired RunDirectiveRepository directiveRepository;
    @Autowired ToolCallAuditRepository auditRepository;
    @Autowired List<McpServerFeatures.SyncToolSpecification> toolSpecs;
    @MockitoBean PermissionClient permissionClient;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        given(permissionClient.checkAdmin(anyLong(), any(), any())).willReturn(PermissionDecision.deny("NO_GRANT"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        directiveRepository.deleteAll();
    }

    private Run run(long projectId, boolean running) {
        Run run = Run.queued(RunType.TASK, "AGP-" + System.nanoTime() % 100000, projectId, 5L, RunTrigger.USER, "harness://default", null);
        if (running) {
            run.start("pending", null);
        }
        return runRepository.save(run);
    }

    private static String body(String text) {
        return "{\"text\":\"" + text + "\"}";
    }

    @Test
    void project_admin_posts_directive_to_running_run_and_gets_201_shape() throws Exception {
        Run run = run(31L, true);
        given(permissionClient.checkAdmin(2L, ResourceType.PROJECT, "31")).willReturn(PermissionDecision.allow());

        mvc.perform(post("/api/agent/runs/" + run.getId() + "/directives").with(authentication(TestAuth.user(2L, "Bob")))
                        .contentType(MediaType.APPLICATION_JSON).content(body("  테스트부터 고쳐  ")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.runId").value(run.getId()))
                .andExpect(jsonPath("$.text").value("테스트부터 고쳐"))
                .andExpect(jsonPath("$.textRedacted").value(false))
                .andExpect(jsonPath("$.authorMemberId").value(2))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.deliveredAt").value(nullValue()));

        List<ToolCallAudit> audits = auditRepository.findAll().stream().filter(a -> "run.directive".equals(a.getTool())).toList();
        assertThat(audits).isNotEmpty();
        assertThat(audits.getLast().getSummary()).startsWith("directive created run=" + run.getId()).doesNotContain("테스트부터");
    }

    @Test
    void non_admin_is_forbidden_and_nothing_is_saved() throws Exception {
        Run run = run(32L, true);

        mvc.perform(post("/api/agent/runs/" + run.getId() + "/directives").with(authentication(TestAuth.user(3L, "Eve")))
                        .contentType(MediaType.APPLICATION_JSON).content(body("x")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("이 프로젝트의 관리자만 할 수 있습니다"));

        assertThat(directiveRepository.findByRunIdOrderByIdAsc(run.getId())).isEmpty();
    }

    @Test
    void non_running_run_is_409_with_clear_message() throws Exception {
        Run queued = run(33L, false);

        mvc.perform(post("/api/agent/runs/" + queued.getId() + "/directives").with(authentication(TestAuth.admin(1L, "Admin")))
                        .contentType(MediaType.APPLICATION_JSON).content(body("x")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.startsWith(
                        "실행 중(RUNNING)인 run에만 지시할 수 있습니다(현재: QUEUED)")));
    }

    @Test
    void invalid_text_is_400_without_echoing_it_and_missing_run_is_404() throws Exception {
        Run run = run(34L, true);
        String url = "/api/agent/runs/" + run.getId() + "/directives";

        mvc.perform(post(url).with(authentication(TestAuth.admin(1L, "Admin")))
                        .contentType(MediaType.APPLICATION_JSON).content(body("   ")))
                .andExpect(status().isBadRequest());
        mvc.perform(post(url).with(authentication(TestAuth.admin(1L, "Admin")))
                        .contentType(MediaType.APPLICATION_JSON).content(body("a".repeat(2001))))
                .andExpect(status().isBadRequest());
        mvc.perform(post(url).with(authentication(TestAuth.admin(1L, "Admin")))
                        .contentType(MediaType.APPLICATION_JSON).content(body("비밀\\u0000값")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("입력 인코딩 거부 — text: 제어문자 U+0000 (3번째 문자)"));
        mvc.perform(post("/api/agent/runs/999999/directives").with(authentication(TestAuth.admin(1L, "Admin")))
                        .contentType(MediaType.APPLICATION_JSON).content(body("x")))
                .andExpect(status().isNotFound());

        assertThat(directiveRepository.findByRunIdOrderByIdAsc(run.getId())).isEmpty();
    }

    @Test
    void list_shows_text_to_project_admin_and_only_metadata_to_others() throws Exception {
        Run run = run(35L, true);
        directiveRepository.save(RunDirective.of(run.getId(), "사람 지시 본문", 2L));
        given(permissionClient.checkAdmin(2L, ResourceType.PROJECT, "35")).willReturn(PermissionDecision.allow());
        String url = "/api/agent/runs/" + run.getId() + "/directives";

        mvc.perform(get(url).with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].text").value("사람 지시 본문"))
                .andExpect(jsonPath("$[0].textRedacted").value(false));
        mvc.perform(get(url).with(authentication(TestAuth.user(3L, "Eve"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].text").value(nullValue()))
                .andExpect(jsonPath("$[0].textRedacted").value(true))
                .andExpect(jsonPath("$[0].runId").value(run.getId()))
                .andExpect(jsonPath("$[0].deliveredAt").value(nullValue()));

        // org 장애 중에도 목록은 깨지지 않는다 — 본문만 가린다.
        given(permissionClient.checkAdmin(4L, ResourceType.PROJECT, "35")).willThrow(new ServiceUnavailableException("org down"));
        mvc.perform(get(url).with(authentication(TestAuth.user(4L, "Kim"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].textRedacted").value(true));
    }

    // ---- 실제 도구 목록을 거친 전달(단일 지점 배선) ----

    private McpSchema.CallToolResult callPing(PatPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        McpServerFeatures.SyncToolSpecification ping = toolSpecs.stream()
                .filter(s -> "ping".equals(s.tool().name())).findFirst().orElseThrow();
        return ping.callHandler().apply(null, new McpSchema.CallToolRequest("ping", Map.of()));
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().getLast()).text();
    }

    @Test
    void registered_tools_deliver_once_and_only_to_the_matching_run_token() {
        Run mine = run(36L, true);
        Run other = run(36L, true);
        directiveRepository.save(RunDirective.of(mine.getId(), "지금 멈추고 테스트 먼저", 2L));

        assertThat(text(callPing(new PatPrincipal(100L, 5L, 42L)))).doesNotContain("[사람 지시");
        assertThat(text(callPing(new PatPrincipal(0L, 5L, 42L, true, other.getId())))).doesNotContain("[사람 지시");
        assertThat(text(callPing(new PatPrincipal(0L, 5L, 42L, true, mine.getId()))))
                .startsWith("pong from agent-service")
                .endsWith("\n\n[사람 지시 — 지금 반영하라]\n- 지금 멈추고 테스트 먼저");
        assertThat(text(callPing(new PatPrincipal(0L, 5L, 42L, true, mine.getId())))).doesNotContain("[사람 지시");

        assertThat(directiveRepository.findByRunIdOrderByIdAsc(mine.getId())).singleElement()
                .satisfies(d -> assertThat(d.getDeliveredAt()).isNotNull());
        assertThat(auditRepository.findAll()).anySatisfy(a -> {
            assertThat(a.getTool()).isEqualTo("directive.deliver");
            assertThat(a.getSummary()).isEqualTo("directive delivered run=" + mine.getId() + " count=1");
            assertThat(a.getRunId()).isEqualTo(mine.getId());
        });
    }
}
