package com.indraacademy.ias_management.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public class PromotionDecisionRequest {

    @NotEmpty(message = "Decisions list must not be empty")
    @Valid
    private List<Decision> decisions;

    public List<Decision> getDecisions() { return decisions; }
    public void setDecisions(List<Decision> decisions) { this.decisions = decisions; }

    public static class Decision {
        @NotBlank(message = "Student ID is required")
        private String studentId;

        @NotBlank(message = "Action is required")
        private String action; // PROMOTE | DETAIN | PASS_OUT

        public String getStudentId() { return studentId; }
        public void setStudentId(String studentId) { this.studentId = studentId; }

        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
    }
}
