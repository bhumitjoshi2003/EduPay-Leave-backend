package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.MarkBulkResultDTO;

import java.util.List;

/** A bulk marks save was rejected as a whole; nothing was saved. Carries every per-row reason. */
public class MarkValidationException extends RuntimeException {
    private final List<MarkBulkResultDTO.MarkError> errors;

    public MarkValidationException(List<MarkBulkResultDTO.MarkError> errors) {
        super(errors.size() == 1 ? errors.get(0).getReason()
                : errors.size() + " marks could not be saved. Nothing was saved — fix the highlighted rows and try again.");
        this.errors = List.copyOf(errors);
    }

    public List<MarkBulkResultDTO.MarkError> getErrors() {
        return errors;
    }
}
