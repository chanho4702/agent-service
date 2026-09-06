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
 */
@RestController
@RequestMapping("/api/agent/runs")
@RequiredArgsConstructor
public class RunController {

    private final RunService runService;

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable long id) {
        runService.cancel(id);
    }

    @GetMapping
    public List<RunSummaryResponse> list(@RequestParam(required = false) RunStatus status) {
        return runService.list(status);
    }
}
