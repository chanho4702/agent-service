package com.platform.agentservice.run;

import org.springframework.data.jpa.repository.JpaRepository;

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
}
