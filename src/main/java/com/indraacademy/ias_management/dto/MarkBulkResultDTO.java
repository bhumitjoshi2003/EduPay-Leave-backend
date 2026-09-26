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

        /** Position of the rejected entry in the request and its subject entry (Results Phase 1). */
        private Integer index;
        private Long examSubjectEntryId;

        public MarkError(String studentId, String reason) {
            this.studentId = studentId;
            this.reason = reason;
        }

        public MarkError(int index, String studentId, Long examSubjectEntryId, String reason) {
            this(studentId, reason);
            this.index = index;
            this.examSubjectEntryId = examSubjectEntryId;
        }

        public Integer getIndex() { return index; }
        public Long getExamSubjectEntryId() { return examSubjectEntryId; }
        public String getStudentId() { return studentId; }
        public String getReason() { return reason; }
    }
}
