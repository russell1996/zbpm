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
 * Real IT test on PostgreSQL — creates users with literal %, _, \ and searches.
 * Extends PostgresIT for correct @DynamicPropertySource (port 55432 on PG).
 */
class LikeEscapeIT extends PostgresIT {

    @Autowired UiUserService userService;

    // --- Criterion #3: literal % in username — only exact match returned ---

    @Test
    void searchByLiteralPercent_onlyMatchesExactUser() {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        String literalPercentUser = "admin%" + uniqueSuffix;
        String normalUser = "adminA" + uniqueSuffix;

        CreateUiUserDTO dto1 = new CreateUiUserDTO();
        dto1.setUsername(literalPercentUser);
        dto1.setPassword("pass1234");
        dto1.setFullName("Percent User");
        dto1.setRole("USER");
        userService.create(dto1);

        CreateUiUserDTO dto2 = new CreateUiUserDTO();
        dto2.setUsername(normalUser);
        dto2.setPassword("pass1234");
        dto2.setFullName("Normal User");
        dto2.setRole("USER");
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

        CreateUiUserDTO dto1 = new CreateUiUserDTO();
        dto1.setUsername(literalUnderscoreUser);
        dto1.setPassword("pass1234");
        dto1.setFullName("Underscore User");
        dto1.setRole("USER");
        userService.create(dto1);

        CreateUiUserDTO dto2 = new CreateUiUserDTO();
        dto2.setUsername(normalUser);
        dto2.setPassword("pass1234");
        dto2.setFullName("Normal User");
        dto2.setRole("USER");
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

        CreateUiUserDTO dto = new CreateUiUserDTO();
        dto.setUsername(backslashUser);
        dto.setPassword("pass1234");
        dto.setFullName("Backslash User");
        dto.setRole("USER");
        userService.create(dto);

        UiUserQuery query = new UiUserQuery();
        query.setUsername(backslashUser);
        query.setPageIndex(0);
        query.setPageSize(100);

        PagedDataDTO<UiUser> result = userService.find(query);
        assertThat(result.getData()).isNotEmpty();
    }
}
