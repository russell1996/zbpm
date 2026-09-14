package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.UiUserEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UiUserRepository extends JpaRepository<UiUserEntity, UUID>, JpaSpecificationExecutor<UiUserEntity> {

    Optional<UiUserEntity> findByUsername(String username);

    Optional<UiUserEntity> findByEmail(String email);

    boolean existsByUsername(String username);

    /** WO-REG-1: exact-match existence check (stored emails are normalized lowercase). */
    boolean existsByEmail(String email);

    /** WO-REG-4: live SUPER_ADMINs for verification notification. */
    List<UiUserEntity> findByRoleAndActive(String role, boolean active);

    /** WO-REG-5: pending approvals queue, oldest first. */
    List<UiUserEntity> findByRegistrationStatusOrderByCreatedAtAsc(String registrationStatus);

    /** WO-REG-7: stale unverified registrations eligible for TTL cleanup. */
    List<UiUserEntity> findByRegistrationStatusAndCreatedAtBefore(
        String registrationStatus, java.time.Instant cutoff);

    long countByRoleAndActive(String role, boolean active);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    long countByRoleAndActiveAndUserType(String role, boolean active, String userType);

    /**
     * WO-SEC-63: atomically increment token_version to invalidate all outstanding access tokens
     * for the given user. Used by logout and password-change paths. Returns the number of rows
     * updated (0 if user does not exist).
     */
    @Modifying
    @Transactional
    @Query("UPDATE UiUserEntity u SET u.tokenVersion = u.tokenVersion + 1 WHERE u.id = :userId")
    int incrementTokenVersion(@Param("userId") UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM UiUserEntity u WHERE u.role = :role AND u.active = :active AND u.userType = :userType")
    List<UiUserEntity> findByRoleAndActiveAndUserTypeForUpdate(@Param("role") String role, @Param("active") boolean active, @Param("userType") String userType);

    static Specification<UiUserEntity> byUsernameContains(String username) {
        // WO-SEC-17 L3: escape LIKE wildcards + backslash to prevent injection
        String escaped = username.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return (root, query, cb) -> cb.like(cb.lower(root.get("username")), "%" + escaped + "%", '\\');
    }

    /**
     * WO-ACL-17: candidate search covers login, full name AND email — a substring match in
     * any of the three fields. The user sees "Кирилл Пешков" in the dialog and types what
     * they see; searching only by username made that fail. Case-insensitive; LIKE wildcards
     * and backslash stay escaped exactly as in {@link #byUsernameContains} (WO-SEC-17):
     * widening to three fields must not turn them into live wildcards.
     */
    static Specification<UiUserEntity> byCandidateSearchContains(String q) {
        String escaped = q.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        String pattern = "%" + escaped + "%";
        return (root, query, cb) -> cb.or(
            cb.like(cb.lower(root.get("username")), pattern, '\\'),
            cb.like(cb.lower(root.get("fullName")), pattern, '\\'),
            cb.like(cb.lower(root.get("email")), pattern, '\\'));
    }

    static Specification<UiUserEntity> byActive(boolean active) {
        return (root, query, cb) -> cb.equal(root.get("active"), active);
    }
}
