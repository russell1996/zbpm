package com.zorrodev.bpm.contract;

import com.zorrodev.bpm.contract.dto.CreateUiUserDTO;
import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.UpdateUiUserDTO;
import com.zorrodev.bpm.contract.dto.query.UiUserQuery;
import com.zorrodev.bpm.contract.model.UiUser;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.annotation.PutExchange;

import java.util.UUID;

/** UI user management (requires an ADMIN bearer token). */
public interface UserContract {

    @GetExchange("/users")
    PagedDataDTO<UiUser> getUsers(UiUserQuery query);

    @GetExchange("/users/{id}")
    UiUser getUser(@PathVariable UUID id);

    @PostExchange("/users")
    IdDTO createUser(@RequestBody CreateUiUserDTO dto);

    @PutExchange("/users/{id}")
    IdDTO updateUser(@PathVariable UUID id, @RequestBody UpdateUiUserDTO dto);
}
