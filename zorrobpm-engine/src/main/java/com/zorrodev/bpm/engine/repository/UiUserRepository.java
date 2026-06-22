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
        return (root, query, cb) -> cb.like(cb.lower(root.get("username")), "%" + username.toLowerCase() + "%");
    }

    static Specification<UiUserEntity> byActive(boolean active) {
        return (root, query, cb) -> cb.equal(root.get("active"), active);
    }
}
