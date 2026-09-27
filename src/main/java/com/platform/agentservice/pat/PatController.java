package com.platform.agentservice.pat;

import com.platform.agentservice.authz.AgentCaller;
import com.platform.agentservice.pat.dto.PatCreateRequest;
import org.springframework.security.core.Authentication;
import com.platform.agentservice.pat.dto.PatCreatedResponse;
import com.platform.agentservice.pat.dto.PatSummaryResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * PAT(개인 접근 토큰) 발급·조회·철회. 이 컨트롤러는 일반 JWT 인증 경로(SecurityConfig의
 * anyRequest().authenticated())로 보호된다 — {@code /api/agent/mcp}의 PatAuthFilter와는 무관하다.
 * 발급·조회·철회 모두 관리자만(AGP-21 — 목록의 라벨·페르소나 슬러그·사용 시각도 운영 정보라
 * 일반 사용자에게 열어 둘 이유가 없다). P3f부터 "관리자"는 전역 관리자 또는 페르소나 소속 프로젝트의 관리자다 —
 * 공용 페르소나 토큰은 전역 관리자만, 목록은 프로젝트 관리자에게 projectId로 좁혀서만 연다. 해시는 어느 응답에도 노출하지 않는다.
 */
@RestController
@RequestMapping("/api/agent/tokens")
@RequiredArgsConstructor
public class PatController {

    private final PatService patService;

    @PostMapping
    @PreAuthorize("@agentAuthz.canManagePersonaSlug(authentication, #request.personaSlug())")
    @ResponseStatus(HttpStatus.CREATED)
    public PatCreatedResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PatCreateRequest request,
                                     Authentication authentication) {
        // D-P4-3b: 만료 기본 90일·1~365일, 무기한은 전역 관리자 명시 선택만. 발급자 메일은 만료 임박 알림 수신자다.
        return patService.issueForHuman(request, Long.parseLong(jwt.getSubject()), jwt.getClaimAsString("email"),
                AgentCaller.from(authentication).globalAdmin());
    }

    /** projectId 없으면 전체(전역 관리자만), 있으면 그 프로젝트 소속 페르소나의 토큰만. */
    @GetMapping
    @PreAuthorize("@agentAuthz.canListTokens(authentication, #projectId)")
    public List<PatSummaryResponse> list(@RequestParam(required = false) Long projectId) {
        return patService.list(projectId);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@agentAuthz.canManageToken(authentication, #id)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable long id) {
        patService.revoke(id);
    }
}
