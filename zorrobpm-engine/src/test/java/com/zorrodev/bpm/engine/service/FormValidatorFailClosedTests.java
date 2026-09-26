package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-SEC-59 #3: FormValidator must be FAIL-CLOSED. A schema that cannot be parsed (or a required
 * field that is missing) must produce a validation error, NOT an empty error list that lets the
 * submission through. Before the fix the catch block swallowed the parse exception and returned an
 * empty list (fail-open) — a malformed form schema was silently accepted.
 */
class FormValidatorFailClosedTests {

    private final FormValidator validator = new FormValidator(new ObjectMapper());

    @Test
    void brokenSchema_isRejected_notFailOpen() {
        List<ProcessVariable> vars = List.of();
        List<FormValidator.ValidationError> errors = validator.validate("{ this is : not json", vars);
        assertThat(errors).isNotEmpty();
        assertThat(errors).anyMatch(e -> "schema".equals(e.field()));
    }

    @Test
    void validSchema_withRequiredMissing_isRejected() {
        String schema = "{\"components\":[{\"key\":\"name\",\"type\":\"textfield\",\"validate\":{\"required\":true}}]}";
        List<ProcessVariable> vars = List.of(); // "name" not submitted
        List<FormValidator.ValidationError> errors = validator.validate(schema, vars);
        assertThat(errors).isNotEmpty();
    }
}
