package com.zorrodev.bpm.engine.mapper;

import com.zorrodev.bpm.contract.model.UiUser;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import org.springframework.stereotype.Component;

@Component
public class UiUserMapper {

    public UiUser toDTO(UiUserEntity entity) {
        UiUser dto = new UiUser();
        dto.setId(entity.getId());
        dto.setUsername(entity.getUsername());
        dto.setFullName(entity.getFullName());
        dto.setEmail(entity.getEmail());
        dto.setRole(entity.getRole());
        dto.setActive(entity.isActive());
        dto.setForcePasswordChange(entity.isForcePasswordChange());
        dto.setUserType(entity.getUserType());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }
}
