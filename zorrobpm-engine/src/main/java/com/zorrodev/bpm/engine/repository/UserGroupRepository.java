package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.UserGroupEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface UserGroupRepository extends JpaRepository<UserGroupEntity, com.zorrodev.bpm.engine.entity.UserGroupId> {

    @Query("SELECT ug.groupName FROM UserGroupEntity ug WHERE ug.userId = :userId")
    List<String> findGroupNamesByUserId(@Param("userId") UUID userId);
}
