package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.CreateUiUserDTO;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.UpdateUiUserDTO;
import com.zorrodev.bpm.contract.dto.query.UiUserQuery;
import com.zorrodev.bpm.contract.model.UiUser;

import java.util.Optional;
import java.util.UUID;

public interface UiUserService {

    /** Verifies credentials and issues a token; empty when the user is unknown, inactive or the password is wrong. */
    Optional<AuthResponse> login(LoginDTO dto);

    UiUser getById(UUID id);

    UiUser getByUsername(String username);

    PagedDataDTO<UiUser> find(UiUserQuery query);

    /** Creates a user; throws {@link com.zorrodev.bpm.contract.exception.EngineException} if the username is taken. */
    UUID create(CreateUiUserDTO dto);

    /**
     * WO-SEC-58: self-service password change. Verifies the CURRENT password,
     * enforces the same complexity rules as admin-set passwords, clears
     * forcePasswordChange on success. Throws EngineException on a wrong current
     * password or a weak new one.
     */
    UUID changeOwnPassword(UUID userId, String currentPassword, String newPassword);

    UUID update(UUID id, UpdateUiUserDTO dto);
}
