package com.platform.agentservice.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agentservice.TestAuth;
import com.platform.agentservice.authz.PermissionClient;
import com.platform.agentservice.authz.PermissionDecision;
import com.platform.agentservice.execution.ExecutionSite;
import com.platform.agentservice.pat.PatService;
import com.platform.agentservice.pat.PatTokenRepository;
import com.platform.agentservice.pat.dto.PatCreateRequest;
import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRepository;
import com.platform.agentservice.persona.PersonaRole;
import com.platform.agentservice.run.Run;
import com.platform.agentservice.run.RunRepository;
import com.platform.agentservice.run.RunStatus;
import com.platform.agentservice.run.RunTrigger;
import com.platform.agentservice.run.RunType;
import com.platform.proto.org.v1.ResourceType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 토큰 종류 분리(D-P4-3b)를 실제 전체 보안 체인에서 증명한다 — 러너 토큰(agr_)은 러너 프로토콜만, 직원 토큰(agp_)·run 토큰은 MCP만.
 * 러너 관리 API(발급·목록·철회)는 JWT 체인 + 페르소나 관리와 같은 권한(§7). 결과 보고는 배정받은 러너만.
 */
@SpringBootTest
@ActiveProfiles("test")
class RunnerSecurityChainTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired WebApplicationContext context;
    @Autowired PatService patService;
    @Autowired PatTokenRepository patTokenRepository;
    @Autowired PersonaRepository personaRepository;
    @Autowired RunnerRepository runnerRepository;
    @Autowired RunRepository runRepository;
    @MockitoBean PermissionClient permissionClient;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        given(permissionClient.checkAdmin(anyLong(), any(), any())).willReturn(PermissionDecision.deny("NO_GRANT"));
    }

    @AfterEach
    void cleanUp() {
        runRepository.deleteAll();
        patTokenRepository.deleteAll();
        runnerRepository.deleteAll();
        personaRepository.deleteAll();
    }

    private JsonNode issueRunner(Long projectId) throws Exception {
        String body = projectId == null ? "{\"name\":\"내 PC\"}" : "{\"name\":\"내 PC\",\"projectId\":" + projectId + "}";
        String response = mvc.perform(post("/api/agent/runners").with(authentication(TestAuth.admin(1L, "Admin")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response);
    }

    private String humanPat() {
        personaRepository.save(Persona.of(4201L, "chain-bot", PersonaRole.BACKEND, "봇", null, null));
        return patService.issue(new PatCreateRequest("mine", "chain-bot", null), 1L).token();
    }

    @Test
    void runner_token_is_issued_once_with_agr_prefix_and_authenticates_runner_protocol() throws Exception {
        JsonNode created = issueRunner(null);
        String token = created.get("token").asText();
        assertThat(token).startsWith("agr_");
        assertThat(created.get("kind").asText()).isEqualTo("LOCAL");

        mvc.perform(post("/api/agent/runners/heartbeat").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"0.1.0\",\"os\":\"Windows\",\"maxConcurrency\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runnerId").value(created.get("id").asLong()))
                .andExpect(jsonPath("$.offlineAfterSeconds").value(90));

        // 줄 run이 없으면 204
        mvc.perform(post("/api/agent/runners/claim").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // 목록에는 해시·원문이 없고 heartbeat 결과가 보인다
        mvc.perform(get("/api/agent/runners").with(authentication(TestAuth.admin(1L, "Admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("ONLINE"))
                .andExpect(jsonPath("$[0].version").value("0.1.0"))
                .andExpect(jsonPath("$[0].maxConcurrency").value(2))
                .andExpect(jsonPath("$[0].token").doesNotExist())
                .andExpect(jsonPath("$[0].tokenHash").doesNotExist());
    }

    @Test
    void human_pat_is_rejected_on_runner_endpoints() throws Exception {
        String pat = humanPat();
        mvc.perform(post("/api/agent/runners/heartbeat").header("Authorization", "Bearer " + pat))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("유효하지 않은 러너 토큰"));
        mvc.perform(post("/api/agent/runners/claim").header("Authorization", "Bearer " + pat))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/agent/runners/runs/1/result").header("Authorization", "Bearer " + pat)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"exitCode\":0}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void run_token_is_rejected_on_runner_endpoints() throws Exception {
        personaRepository.save(Persona.of(4202L, "run-bot", PersonaRole.BACKEND, "봇", null, null));
        String runToken = patService.issue(new PatCreateRequest("run:77", "run-bot", null), 0L).token();

        mvc.perform(post("/api/agent/runners/claim").header("Authorization", "Bearer " + runToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void user_jwt_is_rejected_on_runner_protocol() throws Exception {
        mvc.perform(post("/api/agent/runners/heartbeat").with(authentication(TestAuth.admin(1L, "Admin"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void runner_token_is_rejected_on_mcp() throws Exception {
        String token = issueRunner(null).get("token").asText();

        mvc.perform(post("/api/agent/mcp").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void human_pat_still_passes_mcp_auth() throws Exception {
        String pat = humanPat();
        int status = mvc.perform(post("/api/agent/mcp/anything").header("Authorization", "Bearer " + pat))
                .andReturn().getResponse().getStatus();
        assertThat(status).isNotEqualTo(401);
    }

    @Test
    void runner_token_is_rejected_on_admin_endpoints() throws Exception {
        String token = issueRunner(null).get("token").asText();
        mvc.perform(get("/api/agent/runners").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void revoked_runner_token_stops_working() throws Exception {
        JsonNode created = issueRunner(null);
        mvc.perform(delete("/api/agent/runners/" + created.get("id").asLong()).with(authentication(TestAuth.admin(1L, "Admin"))))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/agent/runners/heartbeat").header("Authorization", "Bearer " + created.get("token").asText()))
                .andExpect(status().isUnauthorized());
    }

    // ---- 관리 권한(§7) ----

    @Test
    void project_admin_issues_project_runner_but_not_platform_wide_one() throws Exception {
        given(permissionClient.checkAdmin(2L, ResourceType.PROJECT, "7")).willReturn(PermissionDecision.allow());

        mvc.perform(post("/api/agent/runners").with(authentication(TestAuth.user(2L, "Bob")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"bob-pc\",\"projectId\":7}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.projectId").value(7));
        mvc.perform(post("/api/agent/runners").with(authentication(TestAuth.user(2L, "Bob")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"bob-pc\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("전역 관리자만 할 수 있습니다"));
        mvc.perform(post("/api/agent/runners").with(authentication(TestAuth.user(3L, "Eve")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"eve-pc\",\"projectId\":7}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void project_admin_cannot_revoke_platform_wide_runner() throws Exception {
        given(permissionClient.checkAdmin(2L, ResourceType.PROJECT, "7")).willReturn(PermissionDecision.allow());
        long globalRunner = issueRunner(null).get("id").asLong();

        mvc.perform(delete("/api/agent/runners/" + globalRunner).with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isForbidden());
    }

    // ---- 결과 보고 소유 ----

    @Test
    void result_from_other_runner_is_403_and_owner_result_is_idempotent() throws Exception {
        JsonNode owner = issueRunner(null);
        JsonNode other = issueRunner(null);
        Persona persona = personaRepository.save(Persona.of(4203L, "res-bot", PersonaRole.BACKEND, "봇", null, null));
        Run run = Run.queued(RunType.TASK, "PROJECT-1", 1L, persona.getId(), RunTrigger.USER, "harness://default", null);
        run.assignExecutionSite(ExecutionSite.LOCAL);
        run = runRepository.save(run);
        runRepository.claimForRunner(run.getId(), owner.get("id").asLong(), ExecutionSite.LOCAL, RunStatus.QUEUED,
                RunStatus.RUNNING, "pending", Instant.now());
        String body = "{\"exitCode\":1,\"timedOut\":false,\"rawTail\":\"boom\"}";

        mvc.perform(post("/api/agent/runners/runs/" + run.getId() + "/result")
                        .header("Authorization", "Bearer " + other.get("token").asText())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("이 러너가 배정받은 run이 아닙니다"));

        mvc.perform(post("/api/agent/runners/runs/" + run.getId() + "/result")
                        .header("Authorization", "Bearer " + owner.get("token").asText())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.runStatus").value("BLOCKED"));
        mvc.perform(post("/api/agent/runners/runs/" + run.getId() + "/result")
                        .header("Authorization", "Bearer " + owner.get("token").asText())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.duplicate").value(true));
    }
}
