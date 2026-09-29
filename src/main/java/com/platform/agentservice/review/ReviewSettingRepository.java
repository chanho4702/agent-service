package com.platform.agentservice.review;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReviewSettingRepository extends JpaRepository<ReviewSetting, Long> {

    Optional<ReviewSetting> findByScopeAndScopeIdIsNull(ReviewScope scope);

    Optional<ReviewSetting> findByScopeAndScopeId(ReviewScope scope, Long scopeId);
}
