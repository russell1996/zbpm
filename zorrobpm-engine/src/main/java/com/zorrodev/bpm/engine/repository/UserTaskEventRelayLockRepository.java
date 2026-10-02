package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.UserTaskEventRelayLockEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface UserTaskEventRelayLockRepository extends JpaRepository<UserTaskEventRelayLockEntity, Integer> {

    /** Waits for the node that publishes now; held until the end of the transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM UserTaskEventRelayLockEntity e WHERE e.id = :id")
    Optional<UserTaskEventRelayLockEntity> lock(Integer id);
}
