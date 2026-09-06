package com.platform.agentservice.budget;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

public interface UsageLedgerRepository extends JpaRepository<UsageLedger, Long> {

    @Query("""
            select sum(u.costUsd) from UsageLedger u
             where u.scope = :scope and u.scopeId = :scopeId and u.createdAt >= :since
            """)
    Optional<BigDecimal> sumCostSince(@Param("scope") LedgerScope scope,
                                       @Param("scopeId") String scopeId,
                                       @Param("since") Instant since);
}
