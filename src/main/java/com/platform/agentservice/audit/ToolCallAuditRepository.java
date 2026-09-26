package com.platform.agentservice.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ToolCallAuditRepository extends JpaRepository<ToolCallAudit, Long> {

    /**
     * 페르소나별 최근 감사 1건(사무실 말풍선) — 페르소나마다 쿼리를 돌리지 않으려고 max(id) 서브쿼리 한 방으로 뽑는다.
     * 최근 N건을 가져와 앱에서 묶는 방식은 한 페르소나가 창 안에서 N건을 넘기면 나머지 페르소나가 빠진다.
     */
    @Query("""
            select a from ToolCallAudit a
             where a.id in (select max(b.id) from ToolCallAudit b
                             where b.personaId is not null and b.createdAt >= :since
                             group by b.personaId)
            """)
    List<ToolCallAudit> findLatestPerPersonaSince(@Param("since") Instant since);

    List<ToolCallAudit> findTop50ByPersonaIdAndCreatedAtGreaterThanEqualOrderByIdDesc(Long personaId, Instant since);
}
