package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProcessMemberRepository extends JpaRepository<ProcessMemberEntity, ProcessMemberId> {

    List<ProcessMemberEntity> findByProcessId(UUID processId);

    List<ProcessMemberEntity> findByUserId(UUID userId);
}
