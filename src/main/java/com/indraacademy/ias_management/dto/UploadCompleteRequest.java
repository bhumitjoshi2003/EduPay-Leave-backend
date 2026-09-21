package com.indraacademy.ias_management.dto;

import jakarta.validation.constraints.NotBlank;

public class UploadCompleteRequest {

    @NotBlank
    private String objectKey;

    @NotBlank
    private String purpose;

    @NotBlank
    private String entityId;

    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }

    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }

    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId; }
}
