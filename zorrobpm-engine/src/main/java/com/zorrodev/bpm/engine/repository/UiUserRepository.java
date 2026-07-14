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

    static Specification<UiUserEntity> byActive(boolean active) {
        return (root, query, cb) -> cb.equal(root.get("active"), active);
    }
}
