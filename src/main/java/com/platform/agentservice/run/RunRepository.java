package com.platform.agentservice.run;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface RunRepository extends JpaRepository<Run, Long> {
    List<Run> findByStatus(RunStatus status);

    long countByStatusInAndProjectId(Collection<RunStatus> statuses, long projectId);

    long countByStatusIn(Collection<RunStatus> statuses);

    boolean existsByIssueKeyAndStatusIn(String issueKey, Collection<RunStatus> statuses);

    // ---- 사무실 감독 API(P3a) — id는 IDENTITY라 생성 순서와 같다(동시각 createdAt 동률 없이 최신 판정). ----

    List<Run> findByStatusInOrderByIdDesc(Collection<RunStatus> statuses);

    List<Run> findByStatusInAndProjectIdOrderByIdDesc(Collection<RunStatus> statuses, long projectId);

    /** 종결 시각 정렬을 endedAt이 아니라 updatedAt으로 하는 이유: BLOCKED는 {@link Run#block}이 endedAt을 채우지 않는다. */
    List<Run> findTop10ByStatusInOrderByUpdatedAtDescIdDesc(Collection<RunStatus> statuses);

    List<Run> findTop10ByStatusInAndProjectIdOrderByUpdatedAtDescIdDesc(Collection<RunStatus> statuses, long projectId);

    List<Run> findTop20ByPersonaIdOrderByIdDesc(long personaId);
}
