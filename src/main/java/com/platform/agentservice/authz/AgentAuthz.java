package com.platform.agentservice.authz;

import com.platform.agentservice.pat.PatTokenRepository;
import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRepository;
import com.platform.agentservice.persona.dto.PersonaCreateRequest;
import com.platform.agentservice.run.Gate;
import com.platform.agentservice.run.GateRepository;
import com.platform.agentservice.run.Run;
import com.platform.agentservice.run.RunRepository;
import com.platform.common.error.ForbiddenException;
import com.platform.proto.org.v1.ResourceType;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * AI 팀 관리 권한(P3f, AGP-64). 규칙은 하나다 — <b>전역 관리자(JWT ROLE_ADMIN) 또는 대상 프로젝트의 ADMIN</b>
 * (org {@code CheckPermission(PROJECT, id, ADMIN)}, D-P3f-1). 대상 프로젝트는 행위 대상에서 파생한다:
 * 페르소나→소속 프로젝트(null=공용→전역 관리자만), run·게이트→run의 프로젝트, 회의 소집→요청 projectId,
 * USER run→이슈의 프로젝트({@link #requireManageProject}를 서비스가 조회 뒤에 부른다).
 *
 * <p>{@code can*} 메서드는 {@code @PreAuthorize("@agentAuthz.canXxx(authentication, ...)")}에서 부른다 — 허용이면 true,
 * 아니면 {@link ForbiddenException}을 던져 {@code {"error": 사유}} 403이 된다(false로 떨어뜨리면 사유 없는 403이 된다).
 * 대상이 없으면(없는 id) 전역 관리자만 통과시킨다: 프로젝트 관리자에게 "없음(404)"과 "남의 것(403)"을 구분해 주지 않는다.
 *
 * <p>org 가용성 장애는 503({@code ServiceUnavailableException}이 그대로 올라간다 — alm 관례), 그 밖의 판정 실패는 거부
 * (fail-closed). 전역 관리자는 org를 부르지 않고 통과하므로 org 장애 중에도 운영이 멈추지 않는다.
 */
@Component("agentAuthz")
@RequiredArgsConstructor
public class AgentAuthz {

    static final String PROJECT_ADMIN_ONLY = "이 프로젝트의 관리자만 할 수 있습니다";
    static final String GLOBAL_ADMIN_ONLY = "전역 관리자만 할 수 있습니다";
    static final String SHARED_PERSONA_GLOBAL_ONLY = "전사 공용 페르소나는 전역 관리자만 관리할 수 있습니다";
    static final String UNMANAGED_GRANT = "관리하지 않는 자원 권한은 부여할 수 없습니다";
    static final String GLOBAL_GRANT_GLOBAL_ONLY = "GLOBAL 권한은 전역 관리자만 부여할 수 있습니다";

    private final PermissionClient permissions;
    private final PersonaRepository personaRepository;
    private final RunRepository runRepository;
    private final GateRepository gateRepository;
    private final PatTokenRepository patTokenRepository;

    // ---- 판정(던지지 않음) ----

    /** 권한 조회 API용. 판정 실패는 false, 가용성 장애는 503을 던진다(호출측이 삼킬지 정한다). */
    public boolean canManage(AgentCaller caller, long projectId) {
        return caller.globalAdmin() || projectAdmin(caller, projectId).allowed();
    }

    // ---- 서비스 가드 ----

    public void requireManageProject(AgentCaller caller, long projectId) {
        if (caller.globalAdmin()) return;
        PermissionDecision decision = projectAdmin(caller, projectId);
        if (!decision.allowed()) throw forbidden(decision, PROJECT_ADMIN_ONLY);
    }

    /** 소속 프로젝트가 없으면(공용 페르소나·없는 대상) 전역 관리자만. */
    public void requireManagePersona(AgentCaller caller, Persona persona) {
        Long projectId = persona == null ? null : persona.getProjectId();
        if (projectId == null) {
            if (!caller.globalAdmin()) throw new ForbiddenException(SHARED_PERSONA_GLOBAL_ONLY);
            return;
        }
        requireManageProject(caller, projectId);
    }

    // ---- @PreAuthorize 진입점 ----

    /** 전사 범위 설정(전역 LLM 키 등, P3h) — 프로젝트 축이 없으니 전역 관리자만. */
    public boolean canManageGlobal(Authentication authentication) {
        requireGlobal(AgentCaller.from(authentication));
        return true;
    }

    public boolean canManageProject(Authentication authentication, Long projectId) {
        AgentCaller caller = AgentCaller.from(authentication);
        if (projectId == null) {
            requireGlobal(caller);
        } else {
            requireManageProject(caller, projectId);
        }
        return true;
    }

    public boolean canManagePersona(Authentication authentication, long personaId) {
        requireManagePersona(AgentCaller.from(authentication), personaRepository.findById(personaId).orElse(null));
        return true;
    }

    public boolean canManagePersonaSlug(Authentication authentication, String slug) {
        Persona persona = slug == null ? null : personaRepository.findBySlug(slug).orElse(null);
        requireManagePersona(AgentCaller.from(authentication), persona);
        return true;
    }

    /**
     * 페르소나 부트스트랩. 기존 슬러그면 표시 필드 갱신뿐이라 그 페르소나의 관리 권한으로 판정한다(요청 projectId로
     * 남의 페르소나를 덮어쓰지 못하게). 새 페르소나면 소속 프로젝트 관리 권한 + 부여 상한(D-P3f-4).
     */
    public boolean canBootstrapPersona(Authentication authentication, PersonaCreateRequest request) {
        AgentCaller caller = AgentCaller.from(authentication);
        Optional<Persona> existing = request.slug() == null ? Optional.empty() : personaRepository.findBySlug(request.slug());
        if (existing.isPresent()) {
            requireManagePersona(caller, existing.get());
            return true;
        }
        if (request.projectId() == null) {
            if (!caller.globalAdmin()) throw new ForbiddenException(SHARED_PERSONA_GLOBAL_ONLY);
        } else {
            requireManageProject(caller, request.projectId());
        }
        requireGrantable(caller, request.grants());
        return true;
    }

    public boolean canManageRun(Authentication authentication, long runId) {
        AgentCaller caller = AgentCaller.from(authentication);
        Optional<Run> run = runRepository.findById(runId);
        if (run.isEmpty()) {
            requireGlobal(caller);
        } else {
            requireManageProject(caller, run.get().getProjectId());
        }
        return true;
    }

    public boolean canManageGate(Authentication authentication, long gateId) {
        AgentCaller caller = AgentCaller.from(authentication);
        Optional<Run> run = gateRepository.findById(gateId).map(Gate::getRunId).flatMap(runRepository::findById);
        if (run.isEmpty()) {
            requireGlobal(caller);
        } else {
            requireManageProject(caller, run.get().getProjectId());
        }
        return true;
    }

    /** 없는 토큰 id는 전역 관리자만 통과(204 멱등 철회) — 프로젝트 관리자에게 토큰 id 존재 여부를 흘리지 않는다. */
    public boolean canManageToken(Authentication authentication, long tokenId) {
        AgentCaller caller = AgentCaller.from(authentication);
        Persona persona = patTokenRepository.findById(tokenId)
                .flatMap(t -> personaRepository.findById(t.getPersonaId()))
                .orElse(null);
        requireManagePersona(caller, persona);
        return true;
    }

    /** 토큰 목록 — 전역 관리자는 projectId 없이 전체, 프로젝트 관리자는 projectId 필수(그 프로젝트 페르소나 토큰만). */
    public boolean canListTokens(Authentication authentication, Long projectId) {
        return canManageProject(authentication, projectId);
    }

    // ---- 내부 ----

    private void requireGlobal(AgentCaller caller) {
        if (!caller.globalAdmin()) throw new ForbiddenException(GLOBAL_ADMIN_ONLY);
    }

    /** 부여 상한 — 자기 권한 이상을 페르소나에 실어 우회하는 경로를 막는다. 전역 관리자는 무엇이든 줄 수 있다. */
    private void requireGrantable(AgentCaller caller, List<PersonaCreateRequest.GrantRequest> grants) {
        if (caller.globalAdmin() || grants == null) return;
        for (PersonaCreateRequest.GrantRequest grant : grants) {
            String type = grant.resourceType() == null ? "" : grant.resourceType().trim().toUpperCase(Locale.ROOT);
            ResourceType resourceType = switch (type) {
                case "PROJECT" -> ResourceType.PROJECT;
                case "SPACE" -> ResourceType.SPACE;
                case "GLOBAL" -> throw new ForbiddenException(GLOBAL_GRANT_GLOBAL_ONLY);
                // 모르는 종류를 org에 넘겨 판정받지 않는다 — 판정 못 하는 권한은 주지 않는다.
                default -> throw new ForbiddenException(UNMANAGED_GRANT);
            };
            PermissionDecision decision = permissions.checkAdmin(caller.userId(), resourceType, grant.resourceId().trim());
            if (!decision.allowed()) throw forbidden(decision, UNMANAGED_GRANT);
        }
    }

    private PermissionDecision projectAdmin(AgentCaller caller, long projectId) {
        return permissions.checkAdmin(caller.userId(), ResourceType.PROJECT, String.valueOf(projectId));
    }

    private static ForbiddenException forbidden(PermissionDecision decision, String fallback) {
        String special = decision.specialMessage();
        return new ForbiddenException(special == null ? fallback : special);
    }
}
