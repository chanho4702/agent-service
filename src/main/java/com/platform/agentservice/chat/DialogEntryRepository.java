package com.platform.agentservice.chat;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/** 모든 조회가 (user_member_id, persona_id)로 시작한다 — 소유자 축을 빼먹은 조회가 생기지 않게 메서드 이름에 박아 둔다. */
public interface DialogEntryRepository extends JpaRepository<DialogEntry, Long> {

    List<DialogEntry> findByUserMemberIdAndPersonaIdOrderByIdDesc(long userMemberId, long personaId, Pageable page);

    List<DialogEntry> findByUserMemberIdAndPersonaIdAndIdLessThanOrderByIdDesc(long userMemberId, long personaId, long before,
                                                                               Pageable page);

    /** 수다 문맥 — 같은 세션의 SAY 턴만. */
    List<DialogEntry> findByUserMemberIdAndPersonaIdAndSessionIdAndKindOrderByIdDesc(long userMemberId, long personaId,
                                                                                    String sessionId, DialogKind kind,
                                                                                    Pageable page);

    @Query("""
            select d.id from DialogEntry d
             where d.userMemberId = :userMemberId and d.personaId = :personaId
             order by d.id desc
            """)
    List<Long> findIdsNewestFirst(@Param("userMemberId") long userMemberId, @Param("personaId") long personaId, Pageable page);

    @Modifying
    @Query("""
            delete from DialogEntry d
             where d.userMemberId = :userMemberId and d.personaId = :personaId and d.id <= :maxId
            """)
    int deleteOwnedUpTo(@Param("userMemberId") long userMemberId, @Param("personaId") long personaId, @Param("maxId") long maxId);

    @Modifying
    @Query("delete from DialogEntry d where d.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
