package com.zorrodev.bpm.rest.configuration;

import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.http.MediaType;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-DB-1: a human-written {@code RAISE EXCEPTION} from a DB trigger
 * (SQLState P0001) must surface as {@code 409 DATABASE_RULE_VIOLATION}
 * with the rule text — not as a bare {@code 500 INTERNAL_ERROR}.
 * Everything else (other SQLStates, no PSQLException in the chain)
 * must keep falling into the pre-existing generic catch-all (WO-SEC-17 M6
 * not weakened).
 *
 * <p>Proven through a real DispatcherServlet + advice wiring (standalone
 * MockMvc), not by calling handler methods directly: the negative cases
 * specifically prove the non-P0001 path still lands in the generic
 * {@code 500 INTERNAL_ERROR} response.
 */
class GlobalExceptionHandlerDbRuleTest {

    private static PSQLException pgError(String sqlState, String text) {
        // V3-protocol ErrorResponse body, exactly what the server sends:
        // <field-type><string>\0 ... \0 terminator. S=severity, C=SQLState, M=message.
        String proto = "S" + "ERROR" + "\0" + "C" + sqlState + "\0" + "M" + text + "\0" + "\0";
        return new PSQLException(new ServerErrorMessage(proto));
    }

    @RestController
    static class BoomController {
        // NOTE: PSQLException is checked, so like in production (where Hibernate
        // JDBCExceptions sit between JpaSystemException and the driver error)
        // it travels nested inside runtime intermediaries — which is exactly
        // what the handler's getCause()-chain walk must traverse.
        @GetMapping("/boom-p0001")
        public String p0001() {
            throw new JpaSystemException(
                new RuntimeException(pgError("P0001", "Custom rule violated")));
        }

        @GetMapping("/boom-unique")
        public String unique() {
            throw new JpaSystemException(new RuntimeException(
                pgError("23505", "duplicate key value violates unique constraint \"x\"")));
        }

        @GetMapping("/boom-plain")
        public String plain() {
            throw new JpaSystemException(new RuntimeException("some non-SQL failure"));
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new BoomController())
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();

    @Test
    void p0001_returns409WithRuleText() throws Exception {
        mvc.perform(get("/boom-p0001").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code", is("DATABASE_RULE_VIOLATION")))
            .andExpect(jsonPath("$.message", is("Custom rule violated")));
    }

    @Test
    void otherSqlState_stillGeneric500() throws Exception {
        mvc.perform(get("/boom-unique").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code", is("INTERNAL_ERROR")))
            .andExpect(jsonPath("$.message", is("An unexpected error occurred")));
    }

    @Test
    void noPsqlExceptionInChain_stillGeneric500() throws Exception {
        mvc.perform(get("/boom-plain").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code", is("INTERNAL_ERROR")))
            .andExpect(jsonPath("$.message", is("An unexpected error occurred")));
    }
}
