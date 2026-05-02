package com.indraacademy.ias_management.dto;

import com.indraacademy.ias_management.entity.DemoRequestStatus;
import jakarta.validation.constraints.NotNull;

public class DemoStatusUpdateDTO {

    @NotNull
    private DemoRequestStatus status;

    public DemoRequestStatus getStatus() { return status; }
    public void setStatus(DemoRequestStatus status) { this.status = status; }
}
