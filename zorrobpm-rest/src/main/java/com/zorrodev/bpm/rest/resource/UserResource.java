package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.UserContract;
import com.zorrodev.bpm.contract.dto.CreateUiUserDTO;
import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.UpdateUiUserDTO;
import com.zorrodev.bpm.contract.dto.query.UiUserQuery;
import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.contract.model.UiUser;
import com.zorrodev.bpm.engine.service.UiUserService;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class UserResource implements UserContract {

    private final UiUserService userService;

    @Override
    public PagedDataDTO<UiUser> getUsers(@ParameterObject UiUserQuery query) {
        return userService.find(query);
    }

    @Override
    public UiUser getUser(@PathVariable UUID id) {
        try {
            return userService.getById(id);
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
    }

    @Override
    public IdDTO createUser(@RequestBody CreateUiUserDTO dto) {
        try {
            return id(userService.create(dto));
        } catch (EngineException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @Override
    public IdDTO updateUser(@PathVariable UUID id, @RequestBody UpdateUiUserDTO dto) {
        try {
            return id(userService.update(id, dto));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
    }

    private IdDTO id(UUID value) {
        IdDTO result = new IdDTO();
        result.setId(value);
        return result;
    }
}
