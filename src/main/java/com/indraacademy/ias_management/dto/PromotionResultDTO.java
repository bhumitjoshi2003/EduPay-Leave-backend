package com.indraacademy.ias_management.dto;

import java.util.List;

public class PromotionResultDTO {

    private int promoted;
    private int detained;
    private int passedOut;
    private List<PromotionError> errors;

    public PromotionResultDTO(int promoted, int detained, int passedOut, List<PromotionError> errors) {
        this.promoted = promoted;
        this.detained = detained;
        this.passedOut = passedOut;
        this.errors = errors;
    }

    public int getPromoted() { return promoted; }
    public int getDetained() { return detained; }
    public int getPassedOut() { return passedOut; }
    public List<PromotionError> getErrors() { return errors; }

    public static class PromotionError {
        private String studentId;
        private String reason;

        public PromotionError(String studentId, String reason) {
            this.studentId = studentId;
            this.reason = reason;
        }

        public String getStudentId() { return studentId; }
        public String getReason() { return reason; }
    }
}
