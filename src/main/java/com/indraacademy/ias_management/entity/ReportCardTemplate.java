package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "report_card_template")
@Data
public class ReportCardTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "assessment_group_id", nullable = false)
    private Long assessmentGroupId;

    /** Overrides the school-level grading system for this template.
     *  Null = use school default (CBSE / LETTER / PERCENTAGE). */
    @Column(name = "grading_override", length = 20)
    private String gradingOverride;

    @Column(name = "is_default", nullable = false)
    private Boolean isDefault = false;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    /** JSON blob: { primaryColor, accentColor, showWatermark, watermarkText, footerText, showCgpa, showGradePoints } */
    @Column(name = "branding_json", columnDefinition = "TEXT")
    private String brandingJson;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public ReportCardTemplate() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }
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
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public String getBrandingJson() { return brandingJson; }
    public void setBrandingJson(String brandingJson) { this.brandingJson = brandingJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
