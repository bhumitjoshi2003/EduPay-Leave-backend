package com.indraacademy.ias_management.dto;

import jakarta.validation.constraints.NotNull;

/** Admin request to correct a still-future PLANNED enrollment's target class/section
 *  before it becomes effective. See {@code StudentEnrollmentService.correctPlannedEnrollment}. */
public class CorrectPlannedEnrollmentRequest {
    @NotNull private Long targetClassId;
    private Long targetSectionId;

    public Long getTargetClassId() { return targetClassId; }
    public void setTargetClassId(Long targetClassId) { this.targetClassId = targetClassId; }
    public Long getTargetSectionId() { return targetSectionId; }
    public void setTargetSectionId(Long targetSectionId) { this.targetSectionId = targetSectionId; }
}
