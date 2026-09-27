package com.platform.agentservice.pat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PatTokenRepository extends JpaRepository<PatToken, Long> {
    Optional<PatToken> findByTokenHash(String tokenHash);

    /** 만료 임박 알림 대상(D-P4-3b) — 철회되지 않았고 아직 경고하지 않았으며 [from, to) 안에 만료되는 토큰. run 토큰은 호출부가 거른다. */
    List<PatToken> findByRevokedAtIsNullAndExpiryWarnedAtIsNullAndExpiresAtGreaterThanEqualAndExpiresAtLessThan(
            Instant from, Instant to);
}
