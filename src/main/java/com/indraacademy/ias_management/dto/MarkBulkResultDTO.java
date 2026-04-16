package com.indraacademy.ias_management.dto;

import java.util.List;

/** Response from POST /api/marks/bulk. */
public class MarkBulkResultDTO {

    private int saved;
    private int updated;
    private List<MarkError> errors;

    public MarkBulkResultDTO(int saved, int updated, List<MarkError> errors) {
        this.saved = saved;
        this.updated = updated;
        this.errors = errors;
    }

    public int getSaved() { return saved; }
    public int getUpdated() { return updated; }
    public List<MarkError> getErrors() { return errors; }

    public static class MarkError {
        private String studentId;
        private String reason;

        public MarkError(String studentId, String reason) {
            this.studentId = studentId;
            this.reason = reason;
        }

        public String getStudentId() { return studentId; }
        public String getReason() { return reason; }
    }
}
