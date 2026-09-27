package com.platform.agentservice.runner;

import com.platform.agentservice.runner.dto.RunnerClaimResponse;
import com.platform.agentservice.runner.dto.RunnerHeartbeatRequest;
import com.platform.agentservice.runner.dto.RunnerHeartbeatResponse;
import com.platform.agentservice.runner.dto.RunnerResultRequest;
import com.platform.agentservice.runner.dto.RunnerResultResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 러너 프로토콜(P4a AGP-69) — {@code agr_} 러너 토큰 전용 체인({@link RunnerAuthFilter})에서만 닿는다. 사람용 PAT·run 토큰·JWT로는
 * 부를 수 없다(401). 경로 정본은 {@link RunnerPaths}.
 */
@RestController
@RequestMapping(RunnerPaths.BASE)
@RequiredArgsConstructor
public class RunnerProtocolController {

    private final RunnerService runnerService;
    private final HarnessBundleService harnessBundleService;

    @PostMapping("/heartbeat")
    public RunnerHeartbeatResponse heartbeat(@AuthenticationPrincipal RunnerPrincipal principal,
                                             @RequestBody(required = false) RunnerHeartbeatRequest request) {
        return runnerService.heartbeat(principal, request);
    }

    /** 줄 run이 있으면 200 + 작업 명세, 없으면 204(러너는 잠시 뒤 다시 부른다). */
    @PostMapping("/claim")
    public ResponseEntity<RunnerClaimResponse> claim(@AuthenticationPrincipal RunnerPrincipal principal) {
        return runnerService.claim(principal)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/runs/{id}/result")
    public RunnerResultResponse result(@AuthenticationPrincipal RunnerPrincipal principal, @PathVariable long id,
                                       @Valid @RequestBody RunnerResultRequest request) {
        return runnerService.reportResult(principal, id, request);
    }

    /** 하네스 번들 zip — claim 명세의 {@code harness.sha256}과 같은 ETag. 러너는 해시가 바뀌었을 때만 받는다. */
    @GetMapping("/harness")
    public ResponseEntity<byte[]> harness() {
        HarnessBundleService.Bundle bundle = harnessBundleService.current();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.ETAG, "\"" + bundle.sha256() + "\"")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"harness.zip\"")
                .body(bundle.zip());
    }
}
