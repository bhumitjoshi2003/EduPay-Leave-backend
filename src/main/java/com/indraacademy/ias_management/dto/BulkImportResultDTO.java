package com.indraacademy.ias_management.dto;

import java.util.List;

public class BulkImportResultDTO {

    private int totalRows;
    private int successful;
    private int failed;
    private List<RowError> errors;
    private List<RowSuccess> created;
    /** Non-null only when the uploaded CSV still had a legacy ID column (e.g. "Student ID" /
     *  "Teacher ID") — the column is accepted for backward compatibility but its values are
     *  never used; this explains that plainly rather than silently dropping them. */
    private String notice;

    public BulkImportResultDTO(int totalRows, int successful, int failed, List<RowError> errors,
                                List<RowSuccess> created, String notice) {
        this.totalRows = totalRows;
        this.successful = successful;
        this.failed = failed;
        this.errors = errors;
        this.created = created;
        this.notice = notice;
    }

    public int getTotalRows()            { return totalRows; }
    public int getSuccessful()           { return successful; }
    public int getFailed()               { return failed; }
    public List<RowError> getErrors()    { return errors; }
    public List<RowSuccess> getCreated() { return created; }
    public String getNotice()            { return notice; }

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

    /** One successfully created account — reports the Edunexify-generated ID back to the
     *  admin, since the import request no longer supplies (or honors) one. */
    public static class RowSuccess {
        private int row;
        private String name;
        private String generatedId;

        public RowSuccess(int row, String name, String generatedId) {
            this.row = row;
            this.name = name;
            this.generatedId = generatedId;
        }

        public int getRow()             { return row; }
        public String getName()         { return name; }
        public String getGeneratedId()  { return generatedId; }
    }
}
