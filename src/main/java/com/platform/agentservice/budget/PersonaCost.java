package com.platform.agentservice.budget;

import java.math.BigDecimal;

/** {@link UsageLedgerRepository#sumCostByPersonaSince} 집계 행. */
public record PersonaCost(Long personaId, BigDecimal costUsd) {
}
