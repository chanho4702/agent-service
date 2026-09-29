package com.platform.agentservice.directive.dto;

import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/agent/runs/{id}/directives} 요청(AGP-67). 상한은 {@code run_directive.text VARCHAR(2000)}과 같다
 * ({@code RequestDtoColumnWidthTest}). 공백·인코딩 검사는 서비스가 한다({@code RunDirectiveService.checkText}).
 */
public record RunDirectiveCreateRequest(
        @Size(max = 2000, message = "text는 2000자 이하여야 합니다") String text) {
}
