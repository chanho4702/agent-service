package com.platform.agentservice.pat;

import com.platform.agentservice.pat.dto.PatCreateRequest;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * PAT(개인 접근 토큰) 발급·조회·철회. 이 컨트롤러는 일반 JWT 인증 경로(SecurityConfig의
 * anyRequest().authenticated())로 보호된다 — {@code /api/agent/mcp}의 PatAuthFilter와는 무관하다.
 * 발급·철회는 관리자만, 목록 조회는 인증된 사용자 누구나(해시는 절대 노출하지 않는다).
 */
@RestController
@RequestMapping("/api/agent/tokens")
@RequiredArgsConstructor
public class PatController {

    private final PatService patService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public PatCreatedResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PatCreateRequest request) {
        return patService.issue(request, Long.parseLong(jwt.getSubject()));
    }

    @GetMapping
    public List<PatSummaryResponse> list() {
        return patService.list();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable long id) {
        patService.revoke(id);
    }
}
