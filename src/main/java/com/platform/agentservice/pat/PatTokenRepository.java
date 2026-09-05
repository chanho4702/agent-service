package com.platform.agentservice.pat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PatTokenRepository extends JpaRepository<PatToken, Long> {
    Optional<PatToken> findByTokenHash(String tokenHash);
}
