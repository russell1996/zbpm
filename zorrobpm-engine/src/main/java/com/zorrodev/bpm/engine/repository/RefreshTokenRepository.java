package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.RefreshTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, UUID> {

    Optional<RefreshTokenEntity> findByTokenHashAndRevokedFalse(String tokenHash);

    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

    /**
     * WO-SEC-55: revoke ALL tokens of a user in their OWN transaction (REQUIRES_NEW).
     * Callers invoke this right before failing the request with 401 (theft detection);
     * without REQUIRES_NEW the surrounding transaction rolls back on the exception and
     * the revoke is silently lost (proven red by RefreshTokenRaceIntegrationTest
     * criterion4_theftReplayOutsideGrace_revokesAllUserTokens on the pre-fix code).
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("UPDATE RefreshTokenEntity r SET r.revoked = true WHERE r.userId = :userId AND r.revoked = false")
    void revokeAllByUserId(@Param("userId") UUID userId);

    /**
     * WO-SEC-55: atomically claim a refresh token for rotation.
     * Single-statement UPDATE with a WHERE guard — exactly one concurrent caller gets
     * 1 row; concurrent callers with the same (already claimed/revoked) token get 0
     * and must be rejected (no double-spend).
     */
    @Modifying
    @Query("UPDATE RefreshTokenEntity r SET r.revoked = true, r.revokedAt = :now "
            + "WHERE r.tokenHash = :tokenHash AND r.revoked = false")
    int markRevokedByTokenHash(@Param("tokenHash") String tokenHash, @Param("now") Instant now);
}
