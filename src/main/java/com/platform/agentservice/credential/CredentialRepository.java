package com.platform.agentservice.credential;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CredentialRepository extends JpaRepository<Credential, Long> {

    /** 파생 쿼리의 {@code scopeId = :null}은 NULL과 매칭되지 않으므로 PLATFORM(scope_id NULL)은 따로 둔다. */
    @Query("select c from Credential c where c.scope = com.platform.agentservice.credential.CredentialScope.PLATFORM "
            + "and c.scopeId is null and c.provider = :provider")
    Optional<Credential> findPlatform(@Param("provider") LlmProvider provider);

    @Query("select c from Credential c where c.scope = com.platform.agentservice.credential.CredentialScope.PROJECT "
            + "and c.scopeId = :scopeId and c.provider = :provider")
    Optional<Credential> findProject(@Param("scopeId") String scopeId, @Param("provider") LlmProvider provider);
}
