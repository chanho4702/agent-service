package com.platform.agentservice.budget;

import com.platform.agentservice.budget.dto.BudgetStatusResponse;
import com.platform.agentservice.budget.dto.KillSwitchRequest;
import com.platform.agentservice.budget.dto.KillSwitchResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 예산·킬 스위치 감독 API(P2a T5). 킬 스위치 on/off는 관리자만, 두 조회 GET은 인증된
 * 사용자 누구나(RunController의 목록 조회와 같은 패턴). 알림(경고 채널 연동)은 P3 —
 * 이 컨트롤러는 폴링용 supervision 표면일 뿐이다.
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class BudgetController {

    private final BudgetService budgetService;

    @GetMapping("/budget")
    public BudgetStatusResponse budget() {
        BudgetService.BudgetSnapshot snapshot = budgetService.snapshot();
        return new BudgetStatusResponse(snapshot.monthlyCapUsd(), snapshot.platformMonthToDateUsd(), snapshot.killSwitch());
    }

    @GetMapping("/kill-switch")
    public KillSwitchResponse killSwitchStatus() {
        return new KillSwitchResponse(budgetService.killSwitchOn());
    }

    @PostMapping("/kill-switch")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setKillSwitch(@RequestBody KillSwitchRequest request) {
        budgetService.setKillSwitch(request.on());
    }
}
