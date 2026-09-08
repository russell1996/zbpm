package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * WO-REG-3: self-registration payload — username, password, fullName, email ONLY.
 * Deliberately NO {@code role} field: the server always creates {@code role="USER"},
 * so privilege escalation through this path is structurally impossible, not just
 * validated away. Unknown JSON properties (e.g. a smuggled {@code "role"}) are
 * ignored by Jackson's default lenient binding.
 */
@Getter
@Setter
public class RegisterDTO {
    private String username;
    private String password;
    private String fullName;
    private String email;
}
