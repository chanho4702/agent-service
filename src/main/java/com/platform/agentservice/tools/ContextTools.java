package com.platform.agentservice.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agentservice.client.AlmClient;
import com.platform.agentservice.client.OrgClient;
import com.platform.agentservice.client.TokenService;
import com.platform.agentservice.client.dto.MemberResponse;
import com.platform.agentservice.client.dto.ProjectResponse;
import com.platform.agentservice.client.dto.ProjectSettingsResponse;
import com.platform.agentservice.pat.PatPrincipal;
import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRepository;
import com.platform.common.error.NotFoundException;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 컨텍스트 도구 3종(S10 Task 10). {@code whoami}는 로컬 DB({@link PersonaRepository})만
 * 보고 다운스트림 호출이 없다 — 그래도 {@link IssueTools}/{@link WikiTools}와 같은 패턴을
 * 지키려고 {@link Audited}로 감싼다. {@code list_projects}/{@code get_project_context}는
 * {@link IssueTools}·{@link WikiTools}와 동일하게 페르소나 서비스 토큰
 * ({@link TokenService#bearerFor})으로 alm-backend/org-service를 호출한다.
 *
 * <p><b>{@code get_project_context}가 존재하는 이유(S10)</b>: alm-backend
 * {@code create_issue}는 프로젝트 스킴에 없는 상태/타입/우선순위 id를 400으로 거부한다.
 * 이 도구로 유효한 id 목록·필수 필드·담당자 후보를 먼저 확인하면 그 400을 예방할 수
 * 있다 — {@code instructions}(application.yml)에서 이슈 생성 전에 먼저 부르라고 명시한다.
 */
@Component
public class ContextTools {

    private final AlmClient almClient;
    private final OrgClient orgClient;
    private final TokenService tokenService;
    private final PersonaRepository personaRepository;
    private final Audited audited;
    private final ObjectMapper objectMapper;

    public ContextTools(AlmClient almClient, OrgClient orgClient, TokenService tokenService,
                         PersonaRepository personaRepository, Audited audited, ObjectMapper objectMapper) {
        this.almClient = almClient;
        this.orgClient = orgClient;
        this.tokenService = tokenService;
        this.personaRepository = personaRepository;
        this.audited = audited;
        this.objectMapper = objectMapper;
    }

    @McpTool(name = "whoami", description = "현재 호출 중인 페르소나 정보를 조회한다(slug·이름·역할·memberId).")
    public String whoami() {
        PatPrincipal actor = ToolActor.current();
        return audited.run("whoami", "whoami", () -> {
            Persona persona = personaRepository.findById(actor.personaId())
                    .orElseThrow(() -> new NotFoundException("페르소나를 찾을 수 없습니다: id=" + actor.personaId()));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("slug", persona.getSlug());
            result.put("name", persona.getName());
            result.put("role", persona.getRole());
            result.put("memberId", persona.getMemberId());
            return writeJson(result);
        });
    }

    @McpTool(name = "list_projects", description = "접근 가능한 ALM 프로젝트 목록을 조회한다(id·key·name).")
    public String listProjects() {
        PatPrincipal actor = ToolActor.current();
        return audited.run("list_projects", "list_projects", () -> {
            String bearer = tokenService.bearerFor(actor.personaMemberId());
            List<ProjectResponse> projects = almClient.listProjects(bearer);
            return writeJson(projects.stream().map(this::projectSummary).toList());
        });
    }

    @McpTool(name = "get_project_context",
            description = "이슈 생성 전에 먼저 호출한다 — 프로젝트 정보, 유효 스킴(상태/타입/우선순위/필수 필드), "
                    + "조직 멤버 명단을 한 번에 돌려준다(create_issue의 스킴 400을 예방).")
    public String getProjectContext(@McpToolParam(description = "프로젝트 id", required = true) long projectId) {
        PatPrincipal actor = ToolActor.current();
        return audited.run("get_project_context", "projectId=" + projectId, () -> {
            String bearer = tokenService.bearerFor(actor.personaMemberId());
            ProjectResponse project = almClient.getProject(projectId, bearer);
            ProjectSettingsResponse settings = almClient.getProjectSettings(projectId, bearer);
            List<MemberResponse> members = orgClient.listMembers(bearer);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("project", projectSummary(project));
            result.put("settings", settingsSummary(settings));
            result.put("members", members.stream().map(this::memberSummary).toList());
            return writeJson(result);
        });
    }

    private Map<String, Object> projectSummary(ProjectResponse project) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", project.id());
        row.put("key", project.key());
        row.put("name", project.name());
        return row;
    }

    private Map<String, Object> memberSummary(MemberResponse member) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", member.id());
        row.put("displayName", member.displayName());
        row.put("kind", member.kind());
        return row;
    }

    /**
     * 유효 스킴 요약 — {@code transitions}는 비어 있지 않을 때만, 필수 필드는 하나라도 있을 때만
     * 포함한다(대부분 프로젝트는 전이·필수 필드를 안 쓰므로 응답을 불필요하게 부풀리지 않는다).
     */
    private Map<String, Object> settingsSummary(ProjectSettingsResponse settings) {
        ProjectSettingsResponse.SettingsBody body = settings.body();
        Map<String, Object> result = new LinkedHashMap<>();

        List<String> statuses = body.statuses() == null ? List.of()
                : body.statuses().stream().map(ProjectSettingsResponse.StatusEntry::id).toList();
        result.put("statuses", statuses);

        if (body.transitions() != null && !body.transitions().isEmpty()) {
            result.put("transitions", body.transitions());
        }

        result.put("enabledTypes", body.enabledTypes() == null ? List.of() : body.enabledTypes());
        result.put("enabledPriorities", body.enabledPriorities() == null ? List.of() : body.enabledPriorities());
        result.put("defaultPriority", body.defaultPriority());

        List<String> requiredFields = requiredFieldIds(body.fields());
        if (!requiredFields.isEmpty()) {
            result.put("requiredFields", requiredFields);
        }

        if (body.fieldsByType() != null && !body.fieldsByType().isEmpty()) {
            Map<String, List<String>> requiredByType = new LinkedHashMap<>();
            body.fieldsByType().forEach((type, fields) -> {
                List<String> required = requiredFieldIds(fields);
                if (!required.isEmpty()) {
                    requiredByType.put(type, required);
                }
            });
            if (!requiredByType.isEmpty()) {
                result.put("requiredFieldsByType", requiredByType);
            }
        }

        return result;
    }

    private List<String> requiredFieldIds(List<ProjectSettingsResponse.FieldConfigEntry> fields) {
        if (fields == null) {
            return List.of();
        }
        return fields.stream()
                .filter(ProjectSettingsResponse.FieldConfigEntry::required)
                .map(ProjectSettingsResponse.FieldConfigEntry::id)
                .toList();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("도구 응답 직렬화 실패", e);
        }
    }
}
