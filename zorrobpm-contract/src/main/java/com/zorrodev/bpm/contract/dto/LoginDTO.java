package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginDTO {
    /** WO-AUTH-1: username ИЛИ email пользователя (поле не переименовано — контракт). */
    private String username;
    private String password;
}
