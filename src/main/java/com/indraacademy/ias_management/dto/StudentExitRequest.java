package com.indraacademy.ias_management.dto;

import java.time.LocalDate;

public class StudentExitRequest {
    private String exitType;          // GRADUATED, TRANSFERRED, WITHDRAWN
    private String reasonForLeaving;
    private String conductAtLeaving;  // optional
    private LocalDate leavingDate;
    private String exitRemarks;       // optional

    public String getExitType() { return exitType; }
    public void setExitType(String exitType) { this.exitType = exitType; }

    public String getReasonForLeaving() { return reasonForLeaving; }
    public void setReasonForLeaving(String reasonForLeaving) { this.reasonForLeaving = reasonForLeaving; }

    public String getConductAtLeaving() { return conductAtLeaving; }
    public void setConductAtLeaving(String conductAtLeaving) { this.conductAtLeaving = conductAtLeaving; }

    public LocalDate getLeavingDate() { return leavingDate; }
    public void setLeavingDate(LocalDate leavingDate) { this.leavingDate = leavingDate; }

    public String getExitRemarks() { return exitRemarks; }
    public void setExitRemarks(String exitRemarks) { this.exitRemarks = exitRemarks; }
}
