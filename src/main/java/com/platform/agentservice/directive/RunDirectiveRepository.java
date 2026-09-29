package com.platform.agentservice.directive;

import com.platform.agentservice.run.RunStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface RunDirectiveRepository extends JpaRepository<RunDirective, Long> {

    List<RunDirective> findByRunIdOrderByIdAsc(long runId);

    /** 전달 후보 — 도구 호출마다 도는 빠른 경로(부분 인덱스 {@code idx_run_directive_pending}). 비면 UPDATE를 치지 않는다. */
    List<RunDirective> findByRunIdAndDeliveredAtIsNullOrderByIdAsc(long runId);

    /**
     * 한 건을 전달 처리한다 — 1이면 이 호출이 이겼다. 두 도구 호출이 같은 후보를 동시에 보면 행 잠금 뒤 재평가된 조건
     * ({@code deliveredAt is null})에서 한쪽만 1을 얻는다(두 호출이 받는 지시 집합은 서로소). run이 RUNNING이 아니면(워커가
     * {@code report_result}·{@code request_gate}로 막 멈춘 결과 등) 전달하지 않는다 — 읽을 워커가 없는 결과에 실어 "전달됨"으로
     * 표시하면 사람이 지시가 먹혔다고 오해한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RunDirective d set d.deliveredAt = :now
             where d.id = :id and d.deliveredAt is null
               and exists (select r.id from Run r where r.id = d.runId and r.status = :running)
            """)
    int markDelivered(@Param("id") long id, @Param("now") Instant now, @Param("running") RunStatus running);

    /** 사무실 {@code currentRun.pendingDirectiveCount} — run id별 미전달 개수, 쿼리 1회. 행: [runId, count]. */
    @Query("""
            select d.runId, count(d) from RunDirective d
             where d.runId in :runIds and d.deliveredAt is null
             group by d.runId
            """)
    List<Object[]> countPendingByRunIds(@Param("runIds") Collection<Long> runIds);
}
