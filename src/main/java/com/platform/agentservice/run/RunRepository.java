package com.platform.agentservice.run;

import com.platform.agentservice.execution.ExecutionSite;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

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

    /** 사무실 수다(P3g) 시스템 프롬프트의 "지금 하는 일" — office currentRun과 같은 판정(활성 상태 중 최신). */
    Optional<Run> findFirstByPersonaIdAndStatusInOrderByIdDesc(long personaId, Collection<RunStatus> statuses);

    // ---- 회의 run(P3b) ----

    /** 같은 프로젝트 동시 회의 1건 제한 — 회의 run의 이슈키는 프로젝트 대표 키일 수 있어 이슈 축이 아니라 프로젝트 축으로 본다. */
    boolean existsByProjectIdAndTypeInAndStatusIn(long projectId, Collection<RunType> types, Collection<RunStatus> statuses);

    boolean existsByIssueKeyAndTypeAndStatusIn(String issueKey, RunType type, Collection<RunStatus> statuses);

    /** 에스컬레이션 "관련 롤" — 그 이슈에서 run을 돌린 페르소나. */
    List<Run> findTop50ByIssueKeyOrderByIdDesc(String issueKey);

    /** 회고 자료 — 최근 창 안에 갱신된 run. */
    List<Run> findTop30ByProjectIdAndUpdatedAtGreaterThanEqualOrderByIdDesc(long projectId, Instant since);

    /** 사무실 게시판(D-P3b-7) — 산출물(회의록) 페이지가 있는 완료 회의 run. */
    List<Run> findTop5ByTypeInAndStatusAndOutputPageIdIsNotNullOrderByEndedAtDescIdDesc(
            Collection<RunType> types, RunStatus status);

    List<Run> findTop5ByTypeInAndStatusAndProjectIdAndOutputPageIdIsNotNullOrderByEndedAtDescIdDesc(
            Collection<RunType> types, RunStatus status, long projectId);

    // ---- 사무실 회의실(P3e) — RUNNING 행은 소수라 run(status) 인덱스로 충분하다. ----

    Optional<Run> findFirstByTypeInAndStatusOrderByIdDesc(Collection<RunType> types, RunStatus status);

    Optional<Run> findFirstByTypeInAndStatusAndProjectIdOrderByIdDesc(
            Collection<RunType> types, RunStatus status, long projectId);

    // ---- 실행 위치·러너(P4a AGP-69) ----

    /** 스케줄러 전역 동시성 — 서버 워커 자리를 차지하는 run만 센다(LOCAL은 사용자 PC 몫이라 서버 한도에 넣지 않는다). */
    long countByStatusInAndExecutionSite(Collection<RunStatus> statuses, ExecutionSite site);

    /** 러너 claim 후보(플랫폼 전역 범위) — 고정 러너가 없거나 이 러너에 고정된 것만, FIFO. */
    @Query("select r.id from Run r where r.status = :queued and r.executionSite = :site "
            + "and (r.runnerId is null or r.runnerId = :runnerId) order by r.id asc")
    List<Long> findClaimCandidates(@Param("queued") RunStatus queued, @Param("site") ExecutionSite site,
                                   @Param("runnerId") long runnerId, Pageable page);

    /** 러너 claim 후보(프로젝트 범위 러너). */
    @Query("select r.id from Run r where r.status = :queued and r.executionSite = :site and r.projectId = :projectId "
            + "and (r.runnerId is null or r.runnerId = :runnerId) order by r.id asc")
    List<Long> findClaimCandidatesInProject(@Param("queued") RunStatus queued, @Param("site") ExecutionSite site,
                                            @Param("projectId") long projectId, @Param("runnerId") long runnerId,
                                            Pageable page);

    /**
     * 원자적 배정 — QUEUED이고 위치가 맞고 (고정 러너가 없거나 이 러너)인 행만 RUNNING으로 옮긴다. 조건부 UPDATE 한 문장이라
     * 두 러너가 같은 run을 동시에 집어도 한쪽만 1행을 얻는다(다른 쪽은 0 → 다음 후보). {@code @Version}도 올려 인프로세스
     * {@code RunService.execute}의 낙관적 락과도 서로 배타적이다. 워크스페이스는 {@code Run#start}와 같은 규칙(승계 경로 보존,
     * 없으면 "pending").
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Run r set r.status = :running, r.runnerId = :runnerId, r.startedAt = :now, r.updatedAt = :now, "
            + "r.workspacePath = coalesce(r.workspacePath, :pending), r.patId = null, r.runnerResultAt = null, "
            + "r.version = r.version + 1 "
            + "where r.id = :id and r.status = :queued and r.executionSite = :site "
            + "and (r.runnerId is null or r.runnerId = :runnerId)")
    int claimForRunner(@Param("id") long id, @Param("runnerId") long runnerId, @Param("site") ExecutionSite site,
                       @Param("queued") RunStatus queued, @Param("running") RunStatus running,
                       @Param("pending") String pending, @Param("now") Instant now);

    /** 러너 결과 보고 1회 처리 표식 — 배정받은 러너이고 아직 보고가 없을 때만 1행. 0이면 중복 보고(또는 남의 run). */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Run r set r.runnerResultAt = :now where r.id = :id and r.runnerId = :runnerId and r.runnerResultAt is null")
    int markRunnerResult(@Param("id") long id, @Param("runnerId") long runnerId, @Param("now") Instant now);

    long countByRunnerIdAndStatus(long runnerId, RunStatus status);

    List<Run> findByRunnerIdAndStatus(long runnerId, RunStatus status);

    /** 생존 판정 — 러너에 배정돼 RUNNING인 run. */
    List<Run> findByStatusAndRunnerIdIsNotNull(RunStatus status);

    /** 오프라인 러너의 남은 run 토큰 정리 대상 — 러너 run 중 토큰 참조가 남은 것(상태 무관). */
    List<Run> findByRunnerIdIsNotNullAndPatIdIsNotNull();
}
