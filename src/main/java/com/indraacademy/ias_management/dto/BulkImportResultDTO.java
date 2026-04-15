package com.indraacademy.ias_management.dto;

import java.util.List;

public class BulkImportResultDTO {

    private int totalRows;
    private int successful;
    private int failed;
    private List<RowError> errors;

    public BulkImportResultDTO(int totalRows, int successful, int failed, List<RowError> errors) {
        this.totalRows = totalRows;
        this.successful = successful;
        this.failed = failed;
        this.errors = errors;
    }

    public int getTotalRows()          { return totalRows; }
    public int getSuccessful()         { return successful; }
    public int getFailed()             { return failed; }
    public List<RowError> getErrors()  { return errors; }

    public static class RowError {
        private int row;
        private String studentId;
        private String reason;

        public RowError(int row, String studentId, String reason) {
            this.row = row;
            this.studentId = studentId;
            this.reason = reason;
        }

        public int getRow()          { return row; }
        public String getStudentId() { return studentId; }
        public String getReason()    { return reason; }
    }
}
