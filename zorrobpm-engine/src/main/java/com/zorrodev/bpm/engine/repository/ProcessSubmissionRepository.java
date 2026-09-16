package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.ProcessSubmissionEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProcessSubmissionRepository extends JpaRepository<ProcessSubmissionEntity, UUID> {

    /** Admin review queue — oldest PENDING first (idx_process_submission_queue). */
    List<ProcessSubmissionEntity> findByStatusOrderBySubmittedAtAsc(String status);

    /** WO-ACL-12: whole history, oldest first — for the "ALL" queue filter. */
    List<ProcessSubmissionEntity> findAllByOrderBySubmittedAtAsc();

    /** WO-ACL-12: one PENDING per process key — fast pre-check that renders a clear 409
     *  before the partial unique index wins the race (which would otherwise be a 500). */
    boolean existsByProcessKeyAndStatus(String processKey, String status);

    /** "My submissions" — newest first (idx_process_submission_mine). */
    List<ProcessSubmissionEntity> findBySubmittedByOrderBySubmittedAtDesc(UUID submittedBy);

    /** Latest submission of the same process key by the same user — for the resubmission chain. */
    Optional<ProcessSubmissionEntity> findFirstBySubmittedByAndProcessKeyOrderBySubmittedAtDesc(
            UUID submittedBy, String processKey);

    /**
     * WO-REL-39 (F18): row-locked read of the submission for the CAS decision
     * transition (approve/reject). Same shape as
     * {@code UiUserRepository.findByIdForUpdate}: concurrent decisions serialise
     * on the row; the loser re-reads the decided status and takes the 409 path.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ProcessSubmissionEntity s WHERE s.id = :id")
    Optional<ProcessSubmissionEntity> findByIdForUpdate(UUID id);
}