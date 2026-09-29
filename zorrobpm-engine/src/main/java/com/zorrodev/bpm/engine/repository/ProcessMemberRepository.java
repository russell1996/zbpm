package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ProcessMemberRepository extends JpaRepository<ProcessMemberEntity, ProcessMemberId> {

    List<ProcessMemberEntity> findByProcessId(UUID processId);

    List<ProcessMemberEntity> findByUserId(UUID userId);

    List<ProcessMemberEntity> findByProcessIdInAndUserId(Collection<UUID> processIds, UUID userId);

    /**
     * WO-REL-31 CR-4: membership check in one query against a process registry key
     * (resolving process-id via process.definitionKey in a subquery) instead of the
     * instance→definition→process→membership four-step chain. True only when a
     * process with that key exists AND the user is a member — identical branches
     * to the old chain, which skipped the membership check on any missing link.
     */
    @Query("""
        select count(m) > 0 from ProcessMemberEntity m
        where m.userId = :userId
          and m.processId = (select p.id from ProcessEntity p where p.definitionKey = :definitionKey)
        """)
    boolean isMemberByDefinitionKey(@Param("userId") UUID userId,
            @Param("definitionKey") String definitionKey);
}