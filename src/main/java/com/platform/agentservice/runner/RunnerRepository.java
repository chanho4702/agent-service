package com.platform.agentservice.runner;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RunnerRepository extends JpaRepository<Runner, Long> {

    Optional<Runner> findByTokenHash(String tokenHash);

    List<Runner> findByKind(RunnerKind kind);

    /** 사무실 "러너 대기" 판정용 — 철회되지 않았고 최근 heartbeat가 있는 러너. */
    List<Runner> findByRevokedAtIsNullAndLastHeartbeatAtAfter(Instant since);

    /** 러너는 소수(설치당 수 대~수십 대)라 목록 필터는 서비스가 메모리에서 한다. */
    List<Runner> findAllByOrderByIdAsc();
}
