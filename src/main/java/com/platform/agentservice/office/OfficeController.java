package com.platform.agentservice.office;

import com.platform.agentservice.office.dto.OfficeResponse;
import com.platform.agentservice.office.dto.PersonaActivityResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 사무실 감독 API(P3a) — 읽기 전용이라 run·게이트 목록 조회와 같이 인증된 사용자 누구나
 * ({@code SecurityConfig}의 {@code anyRequest().authenticated()}). 프론트는 10초 폴링한다(D-P3a-1).
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class OfficeController {

    private final OfficeService officeService;

    @GetMapping("/office")
    public OfficeResponse office(@RequestParam(required = false) Long projectId) {
        return officeService.office(projectId);
    }

    @GetMapping("/personas/{id}/activity")
    public PersonaActivityResponse activity(@PathVariable long id) {
        return officeService.activity(id);
    }
}
