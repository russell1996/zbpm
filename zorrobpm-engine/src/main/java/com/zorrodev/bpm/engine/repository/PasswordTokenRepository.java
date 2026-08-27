package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.PasswordTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PasswordTokenRepository extends JpaRepository<PasswordTokenEntity, UUID> {

    /** WO-ACL-18: look up a still-valid (not used) token by its hash — type-agnostic. */
    Optional<PasswordTokenEntity> findByTokenHashAndUsedFalse(String tokenHash);

    /**
     * WO-ACL-18 criterion 9: re-issuing an invitation/reset must invalidate any prior
     * outstanding token for the same user+type, so an old link can no longer be used.
     */
    @Modifying
    @Transactional
    @Query("UPDATE PasswordTokenEntity t SET t.used = true, t.consumedAt = :now " +
           "WHERE t.userId = :userId AND t.type = :type AND t.used = false")
    int invalidateByUserAndType(@Param("userId") UUID userId, @Param("type") String type, @Param("now") Instant now);

    /** WO-ACL-18 criterion 5: true while an unexpired invitation token is outstanding. */
    boolean existsByUserIdAndTypeAndUsedFalseAndExpiresAtAfter(
            @Param("userId") UUID userId, @Param("type") String type, @Param("expiresAt") Instant now);
}
