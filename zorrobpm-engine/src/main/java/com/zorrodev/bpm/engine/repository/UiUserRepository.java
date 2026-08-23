package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.UiUserEntity;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface UiUserRepository extends JpaRepository<UiUserEntity, UUID>, JpaSpecificationExecutor<UiUserEntity> {

    Optional<UiUserEntity> findByUsername(String username);

    boolean existsByUsername(String username);

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
