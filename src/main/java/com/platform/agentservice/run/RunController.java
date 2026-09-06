package com.platform.agentservice.run;

import com.platform.agentservice.run.dto.RunSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * run 감독 API 최소 세트(P2a T4). 취소는 관리자만 — 실행 중인 워커 OS 프로세스를 강제로
 * 죽이지는 않는다({@link RunService#cancel} 참고, P2a 범위 밖). 목록 조회는 인증된
 * 사용자 누구나(일반 JWT 체인 — {@code SecurityConfig}의 {@code anyRequest().authenticated()}).
 *
 * <p><b>재개(fix round 2, P2a T7 재리뷰)</b>: 재시도 한도를 소진해 BLOCKED가 된 run은
 * 게이트 승인 흐름({@link GateController})과는 별개로, 관리자가 사람 확인 후 재개할 수
 * 있어야 한다 — 그 전까지는 Dispatcher가 영구히 손을 떼고, 사람은 그 run이 왜 멈췄는지
 * 확인할 방법만 있고 다시 굴릴 방법이 없는 막다른 골목이었다. {@link RunResumeService#resume}
 * 이 오케스트레이션을 담당한다.
 */
@RestController
@RequestMapping("/api/agent/runs")
@RequiredArgsConstructor
public class RunController {

    private final RunService runService;
    private final RunResumeService runResumeService;

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable long id) {
        runService.cancel(id);
    }

    /** BLOCKED run만 대상 — 그 외 상태면 {@link RunResumeService#resume}이 409를 던진다. */
    @PostMapping("/{id}/resume")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resume(@PathVariable long id) {
        runResumeService.resume(id);
    }

    @GetMapping
    public List<RunSummaryResponse> list(@RequestParam(required = false) RunStatus status) {
        return runService.list(status);
    }
}
