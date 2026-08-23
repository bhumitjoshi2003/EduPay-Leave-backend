package com.indraacademy.ias_management.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public class TeacherExitRequest {
    @NotBlank
    private String reasonForLeaving;

    @NotNull
    private LocalDate leavingDate;

    private String exitRemarks;

    public String getReasonForLeaving() { return reasonForLeaving; }
    public void setReasonForLeaving(String reasonForLeaving) { this.reasonForLeaving = reasonForLeaving; }
    public LocalDate getLeavingDate() { return leavingDate; }
    public void setLeavingDate(LocalDate leavingDate) { this.leavingDate = leavingDate; }
    public String getExitRemarks() { return exitRemarks; }
    public void setExitRemarks(String exitRemarks) { this.exitRemarks = exitRemarks; }
}
