package com.indraacademy.ias_management.dto;

import java.util.List;

/** Request body for POST /api/student-fees/recalculate/preview — a single student's
 * selected months within one session. Read-only: preview never writes anything. */
public class RecalculationPreviewRequestDto {
    private String studentId;
    private String session;
    private List<Integer> months;

    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }

    public String getSession() { return session; }
    public void setSession(String session) { this.session = session; }

    public List<Integer> getMonths() { return months; }
    public void setMonths(List<Integer> months) { this.months = months; }
}
