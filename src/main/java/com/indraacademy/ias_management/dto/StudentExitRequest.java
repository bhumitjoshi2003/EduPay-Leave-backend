package com.indraacademy.ias_management.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public class StudentExitRequest {

    @NotBlank(message = "Exit type is required")
    private String exitType;          // GRADUATED, TRANSFERRED, WITHDRAWN

    @NotBlank(message = "Reason for leaving is required")
    @Size(max = 500, message = "Reason must not exceed 500 characters")
    private String reasonForLeaving;

    @Size(max = 255, message = "Conduct must not exceed 255 characters")
    private String conductAtLeaving;  // optional

    @NotNull(message = "Leaving date is required")
    private LocalDate leavingDate;

    @Size(max = 1000, message = "Remarks must not exceed 1000 characters")
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
