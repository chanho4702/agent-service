package com.platform.agentservice.persona;

import com.platform.agentservice.persona.dto.PersonaCreateRequest;
import com.platform.agentservice.persona.dto.PersonaResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 페르소나 부트스트랩 — 관리자만 호출한다. auth-server 사용자 생성·org-service grant
 * 부여 모두 관리자 권한을 요구해 어차피 다운스트림에서 403이 나지만, 실패 흔적을 줄이려고
 * 이 진입점도 ROLE_ADMIN으로 미리 막아 둔다.
 */
@RestController
@RequestMapping("/api/agent/personas")
@RequiredArgsConstructor
public class PersonaController {

    private final PersonaService personaService;

    /** 기존 슬러그를 갱신한 경우 200, 새로 만든 경우 201. */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PersonaResponse> create(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                                   @Valid @RequestBody PersonaCreateRequest request) {
        PersonaService.BootstrapResult result = personaService.bootstrap(request, authorization);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.persona());
    }

    @GetMapping
    public List<PersonaResponse> list() {
        return personaService.list();
    }
}
