package com.platform.agentservice.run;

import com.platform.agentservice.run.dto.GateSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 게이트 승인 대기열 감독 API(P2a T5). 목록 조회는 인증된 사용자 누구나(RunController와
 * 같은 패턴), 결정(승인/거절)은 관리자만 — 결정자 id는 {@link Jwt#getSubject()}(사람
 * member id)를 그대로 쓴다(PatController의 발급자 id 기록 패턴과 동일).
 */
@RestController
@RequestMapping("/api/agent/gates")
@RequiredArgsConstructor
public class GateController {

    private final GateRepository gateRepository;
    private final RunRepository runRepository;
    private final GateService gateService;

    /** {@code pending} 미지정 또는 {@code false} → 요청순 최신 50건. {@code true} → 아직 결정되지 않은 전체. */
    @GetMapping
    public List<GateSummaryResponse> list(@RequestParam(required = false) Boolean pending) {
        List<Gate> gates = Boolean.TRUE.equals(pending)
                ? gateRepository.findByDecisionIsNull()
                : gateRepository.findTop50ByOrderByRequestedAtDesc();
        return gates.stream().map(this::toSummary).toList();
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        gateService.approve(id, Long.parseLong(jwt.getSubject()));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        gateService.reject(id, Long.parseLong(jwt.getSubject()));
    }

    private GateSummaryResponse toSummary(Gate gate) {
        String issueKey = runRepository.findById(gate.getRunId())
                .map(Run::getIssueKey)
                .orElse(null);
        return GateSummaryResponse.of(gate, issueKey);
    }
}
