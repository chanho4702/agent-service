package com.platform.agentservice.client.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** {@code POST /api/alm/issues/{issueId}/worklogs} 요청 body — alm-backend {@code WorklogRequest}와 동일 shape. */
public record WorklogRequest(BigDecimal hours, String comment, LocalDate workedOn) {
}
