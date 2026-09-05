package com.platform.agentservice.client.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** alm-backend {@code CollaborationService.WorklogResponse}와 동일 shape(S10). */
public record WorklogResponse(long id, long issueId, long authorId, BigDecimal hours, String comment, LocalDate workedOn, Instant createdAt) {
}
