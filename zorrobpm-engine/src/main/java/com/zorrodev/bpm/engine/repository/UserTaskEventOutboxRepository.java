package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.UserTaskEventOutboxEntity;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface UserTaskEventOutboxRepository extends JpaRepository<UserTaskEventOutboxEntity, Long> {

    /** The oldest events first, in the order they were written. */
    List<UserTaskEventOutboxEntity> findAllByOrderBySeqAsc(Limit limit);

    @Modifying
    @Query("UPDATE UserTaskEventOutboxEntity e SET e.attempts = e.attempts + 1, e.lastError = :error WHERE e.seq = :seq")
    void recordFailure(Long seq, String error);
}
