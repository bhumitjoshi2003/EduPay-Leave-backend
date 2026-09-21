package com.indraacademy.ias_management.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class UploadRequestRequest {

    @NotBlank
    private String purpose;

    @NotBlank
    private String entityId;

    /** Preserved only as intent metadata (not used to build the object key) — see
     * ObjectStorageService.buildObjectKey. */
    private String fileName;

    @NotBlank
    private String contentType;

    @NotNull
    @Min(1)
    private Long size;

    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }

    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public Long getSize() { return size; }
    public void setSize(Long size) { this.size = size; }
}
