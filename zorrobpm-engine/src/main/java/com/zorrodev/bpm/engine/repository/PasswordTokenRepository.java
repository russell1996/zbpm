package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.PasswordTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
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

    /**
     * WO-ACL-18 criterion 6: atomically consume a single token. Returns the number of rows
     * updated (0 or 1). Exactly one concurrent caller wins (used=false -> true); all others
     * get 0 and must treat the token as already spent — this is what prevents a double submit
     * on the same reset/invite link under a race.
     */
    @Modifying
    @Transactional
    @Query("UPDATE PasswordTokenEntity t SET t.used = true, t.consumedAt = :now " +
           "WHERE t.tokenHash = :hash AND t.used = false")
    int consumeByTokenHash(@Param("hash") String hash, @Param("now") Instant now);

    /** WO-ACL-18 criterion 5: true while an unexpired invitation token is outstanding. */
    boolean existsByUserIdAndTypeAndUsedFalseAndExpiresAtAfter(
            @Param("userId") UUID userId, @Param("type") String type, @Param("expiresAt") Instant now);

    @Query("SELECT t.userId FROM PasswordTokenEntity t WHERE t.userId IN :userIds AND t.type = :type AND t.used = false AND t.expiresAt > :now")
    List<UUID> findUserIdsWithPendingInvite(@Param("userIds") Collection<UUID> userIds, @Param("type") String type, @Param("now") Instant now);

    /**
     * WO-REG-7: delete all token rows of a user whose stale registration is
     * being cleaned up (password_tokens.user_id has no FK to ui_users, so no
     * cascade — explicit delete, before the user row goes).
     */
    @Modifying
    @Transactional
    void deleteByUserId(@Param("userId") UUID userId);
}
