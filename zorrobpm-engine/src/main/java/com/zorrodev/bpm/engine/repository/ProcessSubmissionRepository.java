package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.ProcessSubmissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProcessSubmissionRepository extends JpaRepository<ProcessSubmissionEntity, UUID> {

    /** Admin review queue — oldest PENDING first (idx_process_submission_queue). */
    List<ProcessSubmissionEntity> findByStatusOrderBySubmittedAtAsc(String status);

    /** "My submissions" — newest first (idx_process_submission_mine). */
    List<ProcessSubmissionEntity> findBySubmittedByOrderBySubmittedAtDesc(UUID submittedBy);

    /** Latest submission of the same process key by the same user — for the resubmission chain. */
    Optional<ProcessSubmissionEntity> findFirstBySubmittedByAndProcessKeyOrderBySubmittedAtDesc(
            UUID submittedBy, String processKey);
}