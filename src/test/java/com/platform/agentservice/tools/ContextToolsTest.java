package com.platform.agentservice.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agentservice.audit.AuditService;
import com.platform.agentservice.audit.AuditStatus;
import com.platform.agentservice.client.AlmClient;
import com.platform.agentservice.client.OrgClient;
import com.platform.agentservice.client.TokenService;
import com.platform.agentservice.client.dto.MemberResponse;
import com.platform.agentservice.client.dto.ProjectResponse;
import com.platform.agentservice.client.dto.ProjectSettingsResponse;
import com.platform.agentservice.pat.PatPrincipal;
import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRepository;
import com.platform.agentservice.persona.PersonaRole;
import com.platform.common.error.ForbiddenException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContextToolsTest {

    private static final long OWNER_MEMBER_ID = 100L;
    private static final long PERSONA_ID = 5L;
    private static final long PERSONA_MEMBER_ID = 42L;
    private static final String BEARER = "Bearer persona-token";

    @Mock AlmClient almClient;
    @Mock OrgClient orgClient;
    @Mock TokenService tokenService;
    @Mock PersonaRepository personaRepository;
    @Mock AuditService auditService;

    private ContextTools contextTools;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        Audited audited = new Audited(auditService);
        contextTools = new ContextTools(almClient, orgClient, tokenService, personaRepository, audited, objectMapper);

        PatPrincipal principal = new PatPrincipal(OWNER_MEMBER_ID, PERSONA_ID, PERSONA_MEMBER_ID);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority("ROLE_AGENT"))));

        org.mockito.Mockito.lenient().when(tokenService.bearerFor(PERSONA_MEMBER_ID)).thenReturn(BEARER);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---- whoami: DB만, 다운스트림 호출 없음 ----

    @Test
    void whoami_returns_slug_name_role_memberId_from_persona_repository_only() throws Exception {
        Persona persona = Persona.of(PERSONA_MEMBER_ID, "backend-bot", PersonaRole.BACKEND, "백엔드봇", "🤖", null);
        when(personaRepository.findById(PERSONA_ID)).thenReturn(Optional.of(persona));

        String result = contextTools.whoami();

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.get("slug").asText()).isEqualTo("backend-bot");
        assertThat(json.get("name").asText()).isEqualTo("백엔드봇");
        assertThat(json.get("role").asText()).isEqualTo("BACKEND");
        assertThat(json.get("memberId").asLong()).isEqualTo(PERSONA_MEMBER_ID);
        org.mockito.Mockito.verifyNoInteractions(almClient, orgClient, tokenService);
        verify(auditService).record(eq(PERSONA_ID), eq(OWNER_MEMBER_ID), eq("whoami"), org.mockito.ArgumentMatchers.anyString(), eq(AuditStatus.OK));
    }

    @Test
    void whoami_returns_error_text_when_persona_missing() {
        when(personaRepository.findById(PERSONA_ID)).thenReturn(Optional.empty());

        String result = contextTools.whoami();

        assertThat(result).contains("오류");
        verify(auditService).record(eq(PERSONA_ID), eq(OWNER_MEMBER_ID), eq("whoami"), org.mockito.ArgumentMatchers.anyString(), eq(AuditStatus.ERROR));
    }

    @Test
    void whoami_throws_forbidden_without_pat_principal() {
        SecurityContextHolder.clearContext();
        org.junit.jupiter.api.Assertions.assertThrows(ForbiddenException.class, () -> contextTools.whoami());
    }

    // ---- list_projects ----

    @Test
    void list_projects_returns_compact_id_key_name() throws Exception {
        when(almClient.listProjects(BEARER)).thenReturn(List.of(
                new ProjectResponse(9L, "PROJ", "프로젝트"),
                new ProjectResponse(10L, "OPS", "운영")));

        String result = contextTools.listProjects();

        JsonNode json = objectMapper.readTree(result);
        assertThat(json).hasSize(2);
        assertThat(json.get(0).get("key").asText()).isEqualTo("PROJ");
        assertThat(json.get(1).get("name").asText()).isEqualTo("운영");
    }

    // ---- get_project_context: 프로젝트 + 설정 + 멤버 통합 ----

    @Test
    void get_project_context_aggregates_project_settings_and_members() throws Exception {
        when(almClient.getProject(9L, BEARER)).thenReturn(new ProjectResponse(9L, "PROJ", "프로젝트"));
        ProjectSettingsResponse settings = new ProjectSettingsResponse(new ProjectSettingsResponse.SettingsBody(
                List.of(new ProjectSettingsResponse.StatusEntry("todo"),
                        new ProjectSettingsResponse.StatusEntry("inprogress"),
                        new ProjectSettingsResponse.StatusEntry("done")),
                List.of(),
                List.of("task", "bug"),
                List.of("highest", "high", "medium", "low", "lowest"),
                "medium",
                List.of(new ProjectSettingsResponse.FieldConfigEntry("assignee", true, true),
                        new ProjectSettingsResponse.FieldConfigEntry("labels", true, false)),
                Map.of()));
        when(almClient.getProjectSettings(9L, BEARER)).thenReturn(settings);
        when(orgClient.listMembers(BEARER)).thenReturn(List.of(
                new MemberResponse(1L, "홍길동", "HUMAN"),
                new MemberResponse(2L, "페르소나봇", "AGENT")));

        String result = contextTools.getProjectContext(9L);

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.get("project").get("key").asText()).isEqualTo("PROJ");
        JsonNode statuses = json.get("settings").get("statuses");
        assertThat(statuses).hasSize(3);
        assertThat(statuses.get(0).asText()).isEqualTo("todo");
        assertThat(json.get("settings").has("transitions")).isFalse();
        assertThat(json.get("settings").get("defaultPriority").asText()).isEqualTo("medium");
        JsonNode requiredFields = json.get("settings").get("requiredFields");
        assertThat(requiredFields).hasSize(1);
        assertThat(requiredFields.get(0).asText()).isEqualTo("assignee");
        assertThat(json.get("members")).hasSize(2);
        assertThat(json.get("members").get(1).get("kind").asText()).isEqualTo("AGENT");
        verify(auditService).record(eq(PERSONA_ID), eq(OWNER_MEMBER_ID), eq("get_project_context"), org.mockito.ArgumentMatchers.anyString(), eq(AuditStatus.OK));
    }

    @Test
    void get_project_context_includes_transitions_and_per_type_required_fields_when_present() throws Exception {
        when(almClient.getProject(9L, BEARER)).thenReturn(new ProjectResponse(9L, "PROJ", "프로젝트"));
        ProjectSettingsResponse settings = new ProjectSettingsResponse(new ProjectSettingsResponse.SettingsBody(
                List.of(new ProjectSettingsResponse.StatusEntry("todo"), new ProjectSettingsResponse.StatusEntry("done")),
                List.of(new ProjectSettingsResponse.TransitionEntry("t1", "완료", List.of("todo"), "done")),
                List.of("task"),
                List.of("medium"),
                "medium",
                List.of(new ProjectSettingsResponse.FieldConfigEntry("assignee", true, false)),
                Map.of("bug", List.of(new ProjectSettingsResponse.FieldConfigEntry("priority", true, true)))));
        when(almClient.getProjectSettings(9L, BEARER)).thenReturn(settings);
        when(orgClient.listMembers(BEARER)).thenReturn(List.of());

        String result = contextTools.getProjectContext(9L);

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.get("settings").get("transitions")).hasSize(1);
        assertThat(json.get("settings").has("requiredFields")).isFalse();
        JsonNode requiredByType = json.get("settings").get("requiredFieldsByType");
        assertThat(requiredByType.get("bug").get(0).asText()).isEqualTo("priority");
    }
}
