package com.indraacademy.ias_management.dto;

import java.util.List;

public class PromotionDecisionRequest {

    private List<Decision> decisions;

    public List<Decision> getDecisions() { return decisions; }
    public void setDecisions(List<Decision> decisions) { this.decisions = decisions; }

    public static class Decision {
        private String studentId;
        private String action; // PROMOTE | DETAIN | PASS_OUT

        public String getStudentId() { return studentId; }
        public void setStudentId(String studentId) { this.studentId = studentId; }

        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
    }
}
