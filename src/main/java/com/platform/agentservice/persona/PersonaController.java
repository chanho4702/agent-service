package com.platform.agentservice.persona;

import com.platform.agentservice.authz.AgentCaller;
import com.platform.agentservice.persona.dto.PersonaActiveRequest;
import com.platform.agentservice.persona.dto.PersonaCreateRequest;
import com.platform.agentservice.persona.dto.PersonaResponse;
import com.platform.agentservice.persona.dto.PersonaUpdateRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 페르소나 부트스트랩·활성 토글 — 전역 관리자 또는 소속 프로젝트 관리자(P3f, {@link com.platform.agentservice.authz.AgentAuthz}).
 * 공용 페르소나(projectId 없음)는 전역 관리자만. 새 페르소나의 grant는 호출자가 ADMIN인 자원만 줄 수 있다(D-P3f-4).
 * 판정을 통과한 요청만 auth·org 내부 경로로 등록한다(CLAUDE.md §7).
 */
@RestController
@RequestMapping("/api/agent/personas")
@RequiredArgsConstructor
public class PersonaController {

    private final PersonaService personaService;

    /** 기존 슬러그를 갱신한 경우 200, 새로 만든 경우 201. */
    @PostMapping
    @PreAuthorize("@agentAuthz.canBootstrapPersona(authentication, #request)")
    public ResponseEntity<PersonaResponse> create(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                                   @Valid @RequestBody PersonaCreateRequest request,
                                                   Authentication authentication) {
        PersonaService.BootstrapResult result = personaService.bootstrap(request, authorization,
                AgentCaller.from(authentication).userId());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.persona());
    }

    /** 활성/비활성 전환(AGP-29) — 비활성 페르소나의 사람용 PAT은 즉시 401이 된다(진행 중 run 토큰은 예외). */
    @PatchMapping("/{id}/active")
    @PreAuthorize("@agentAuthz.canManagePersona(authentication, #id)")
    public PersonaResponse changeActive(@PathVariable long id, @Valid @RequestBody PersonaActiveRequest request) {
        return personaService.changeActive(id, request.active());
    }

    /**
     * 직원 편집(AGP-62) — 표시 필드·기본 모델·스킬·아바타 부분 갱신. 권한은 활성 토글과 같다(소속 프로젝트 관리자, 공용은 전역만).
     * null=그대로, 빈 문자열=지움(name은 400).
     */
    @PatchMapping("/{id}")
    @PreAuthorize("@agentAuthz.canManagePersona(authentication, #id)")
    public PersonaResponse edit(@PathVariable long id, @Valid @RequestBody PersonaUpdateRequest request) {
        return personaService.edit(id, request);
    }

    @GetMapping
    public List<PersonaResponse> list() {
        return personaService.list();
    }
}
