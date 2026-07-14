package com.zorrodev.bpm.contract.exception;

import com.zorrodev.bpm.engine.service.FormValidator;
import java.util.List;

/** WO-FORM-5: Thrown when submitted variables fail form schema validation. */
public class FormValidationException extends RuntimeException {
    private final List<FormValidator.ValidationError> errors;

    public FormValidationException(List<FormValidator.ValidationError> errors) {
        super("Form validation failed");
        this.errors = errors;
    }

    public List<FormValidator.ValidationError> getErrors() {
        return errors;
    }
}
