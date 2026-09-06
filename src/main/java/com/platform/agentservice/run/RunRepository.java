package com.platform.agentservice.run;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface RunRepository extends JpaRepository<Run, Long> {
    List<Run> findByStatus(RunStatus status);

    long countByStatusInAndProjectId(Collection<RunStatus> statuses, long projectId);

    long countByStatusIn(Collection<RunStatus> statuses);

    boolean existsByIssueKeyAndStatusIn(String issueKey, Collection<RunStatus> statuses);
}
