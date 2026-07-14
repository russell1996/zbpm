package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

@Getter
@Setter
@Entity
@IdClass(UserGroupId.class)
@Table(name = "user_group")
public class UserGroupEntity {
    @Id
    private UUID userId;
    @Id
    private String groupName;
}
