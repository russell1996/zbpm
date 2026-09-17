package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.contract.dto.CreateUiUserDTO;
import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.query.UiUserQuery;
import com.zorrodev.bpm.contract.model.UiUser;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.service.UiUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-SEC-17 L3: LIKE wildcards must be escaped in search queries.
 * Real IT test on PostgreSQL - creates users with literal %, _, \ and searches.
 * Extends PostgresIT for correct @DynamicPropertySource (port 55432 on PG).
 *
 * WO-TEST-11: renamed from LikeEscapeIT to LikeEscapePgIT (failsafe only includes
 * the PgIT-suffixed pattern, so the old name never ran in any pipeline) and repaired for the
 * WO-ACL-19 contract (HUMAN users require an email). Usernames and all LIKE-escaping
 * assertions are byte-identical to the original - only the required email and a
 * complexity-compliant password (WO-SEC-46, min 12 chars) were added.
 */
class LikeEscapePgIT extends PostgresIT {

    @Autowired UiUserService userService;

    // WO-SEC-46: "pass1234" (8 chars) is weak - min length is 12.
    private static final String PASSWORD = "Str0ng!Test-Passw0rd";

    private static CreateUiUserDTO newUser(String username, String email, String fullName) {
        CreateUiUserDTO dto = new CreateUiUserDTO();
        dto.setUsername(username);
        dto.setPassword(PASSWORD);
        dto.setEmail(email);
        dto.setFullName(fullName);
        dto.setRole("USER");
        return dto;
    }

    // --- Criterion #3: literal % in username — only exact match returned ---

    @Test
    void searchByLiteralPercent_onlyMatchesExactUser() {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        String literalPercentUser = "admin%" + uniqueSuffix;
        String normalUser = "adminA" + uniqueSuffix;

        CreateUiUserDTO dto1 = newUser(literalPercentUser,
            "like-pct-" + uniqueSuffix + "@example.com", "Percent User");
        userService.create(dto1);

        CreateUiUserDTO dto2 = newUser(normalUser,
            "like-norm-a-" + uniqueSuffix + "@example.com", "Normal User");
        userService.create(dto2);

        UiUserQuery query = new UiUserQuery();
        query.setUsername(literalPercentUser);
        query.setPageIndex(0);
        query.setPageSize(100);

        PagedDataDTO<UiUser> result = userService.find(query);
        List<UiUser> data = result.getData();
        assertThat(data)
            .as("Search for literal '%%' must return only the user with %% in name, not wildcard match")
            .hasSize(1);
        assertThat(data.get(0).getUsername()).isEqualTo(literalPercentUser);
    }

    // --- Criterion #3: literal _ in username — only exact match returned ---

    @Test
    void searchByLiteralUnderscore_onlyMatchesExactUser() {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        String literalUnderscoreUser = "admin_" + uniqueSuffix;
        String normalUser = "adminB" + uniqueSuffix;

        CreateUiUserDTO dto1 = newUser(literalUnderscoreUser,
            "like-us-" + uniqueSuffix + "@example.com", "Underscore User");
        userService.create(dto1);

        CreateUiUserDTO dto2 = newUser(normalUser,
            "like-norm-b-" + uniqueSuffix + "@example.com", "Normal User");
        userService.create(dto2);

        UiUserQuery query = new UiUserQuery();
        query.setUsername(literalUnderscoreUser);
        query.setPageIndex(0);
        query.setPageSize(100);

        PagedDataDTO<UiUser> result = userService.find(query);
        List<UiUser> data = result.getData();
        assertThat(data)
            .as("Search for literal '_' must return only the user with _ in name")
            .hasSize(1);
        assertThat(data.get(0).getUsername()).isEqualTo(literalUnderscoreUser);
    }

    // --- Block 2: literal backslash in username — must not 500 ---

    @Test
    void searchByLiteralBackslash_doesNot500() {
        String uniqueSuffix = UUID.randomUUID().toString().substring(8);
        String backslashUser = "admin\\" + uniqueSuffix;

        CreateUiUserDTO dto = newUser(backslashUser,
            "like-bs-" + uniqueSuffix + "@example.com", "Backslash User");
        userService.create(dto);

        UiUserQuery query = new UiUserQuery();
        query.setUsername(backslashUser);
        query.setPageIndex(0);
        query.setPageSize(100);

        PagedDataDTO<UiUser> result = userService.find(query);
        assertThat(result.getData()).isNotEmpty();
    }
}
