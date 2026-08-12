package com.indraacademy.ias_management.dto;

import java.util.List;

/** Request body for POST /api/student-fees/recalculate/apply. reason is required and
 * non-blank — enforced server-side, never trusted from a client that skips validation.
 * Monetary values are never accepted here: Apply always recomputes from the current
 * configuration, exactly like Preview, and never trusts anything the client sends back
 * from a prior Preview response. */
public class RecalculationApplyRequestDto {
    private String studentId;
    private String session;
    private List<Integer> months;
    private String reason;

    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }

    public String getSession() { return session; }
    public void setSession(String session) { this.session = session; }

    public List<Integer> getMonths() { return months; }
    public void setMonths(List<Integer> months) { this.months = months; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
