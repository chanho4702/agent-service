package com.platform.agentservice.budget;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UsageLedgerRepository extends JpaRepository<UsageLedger, Long> {

    @Query("""
            select sum(u.costUsd) from UsageLedger u
             where u.scope = :scope and u.scopeId = :scopeId and u.createdAt >= :since
            """)
    Optional<BigDecimal> sumCostSince(@Param("scope") LedgerScope scope,
                                       @Param("scopeId") String scopeId,
                                       @Param("since") Instant since);

    /**
     * 페르소나별 비용(사무실 API). 원장에는 페르소나 축이 없어 run을 거쳐 묶는다. 한 run의 비용이 PROJECT·PLATFORM
     * 스코프로 두 번 적재되므로 PLATFORM 행만 센다 — 둘 다 세면 두 배가 된다.
     */
    @Query("""
            select new com.platform.agentservice.budget.PersonaCost(r.personaId, sum(u.costUsd))
              from UsageLedger u join com.platform.agentservice.run.Run r on r.id = u.runId
             where u.scope = com.platform.agentservice.budget.LedgerScope.PLATFORM and u.scopeId = 'platform'
               and u.createdAt >= :since
             group by r.personaId
            """)
    List<PersonaCost> sumCostByPersonaSince(@Param("since") Instant since);

    @Query("""
            select new com.platform.agentservice.budget.PersonaCost(r.personaId, sum(u.costUsd))
              from UsageLedger u join com.platform.agentservice.run.Run r on r.id = u.runId
             where u.scope = com.platform.agentservice.budget.LedgerScope.PLATFORM and u.scopeId = 'platform'
               and u.createdAt >= :since
               and r.projectId = :projectId
             group by r.personaId
            """)
    List<PersonaCost> sumCostByPersonaSinceInProject(@Param("since") Instant since, @Param("projectId") long projectId);

    @Query("""
            select sum(u.costUsd)
              from UsageLedger u join com.platform.agentservice.run.Run r on r.id = u.runId
             where u.scope = com.platform.agentservice.budget.LedgerScope.PLATFORM and u.scopeId = 'platform'
               and u.createdAt >= :since
               and r.personaId = :personaId
            """)
    Optional<BigDecimal> sumCostForPersonaSince(@Param("personaId") long personaId, @Param("since") Instant since);
}
