package com.indraacademy.ias_management.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request body for creating or updating a report card template. */
public class ReportCardTemplateRequest {

    @NotBlank(message = "Template name is required")
    @Size(max = 150)
    private String name;

    @Size(max = 500)
    private String description;

    @NotNull(message = "assessmentGroupId is required")
    private Long assessmentGroupId;

    /** Optional grading override: CBSE, LETTER, PERCENTAGE. Null = use school default. */
    private String gradingOverride;

    private Boolean isDefault = false;

    /** JSON blob for custom branding. Null = use defaults. */
    private String brandingJson;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Long getAssessmentGroupId() { return assessmentGroupId; }
    public void setAssessmentGroupId(Long assessmentGroupId) { this.assessmentGroupId = assessmentGroupId; }

    public String getGradingOverride() { return gradingOverride; }
    public void setGradingOverride(String gradingOverride) { this.gradingOverride = gradingOverride; }

    public Boolean getIsDefault() { return isDefault; }
    public void setIsDefault(Boolean isDefault) { this.isDefault = isDefault; }

    public String getBrandingJson() { return brandingJson; }
    public void setBrandingJson(String brandingJson) { this.brandingJson = brandingJson; }
}
