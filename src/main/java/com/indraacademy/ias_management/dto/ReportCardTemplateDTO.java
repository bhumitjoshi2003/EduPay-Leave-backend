package com.indraacademy.ias_management.dto;

import java.time.LocalDateTime;
import java.util.List;

/** Read-only response DTO for a report card template with its sections. */
public class ReportCardTemplateDTO {

    private Long id;
    private Long schoolId;
    private String name;
    private String description;
    private Long assessmentGroupId;
    private String assessmentGroupName;
    private String gradingOverride;
    private Boolean isDefault;
    private Boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<SectionDTO> sections;
    private String brandingJson;

    public ReportCardTemplateDTO() {}

    public ReportCardTemplateDTO(Long id, Long schoolId, String name, String description,
                                  Long assessmentGroupId, String assessmentGroupName,
                                  String gradingOverride, Boolean isDefault, Boolean isActive,
                                  LocalDateTime createdAt, LocalDateTime updatedAt,
                                  List<SectionDTO> sections, String brandingJson) {
        this.id = id;
        this.schoolId = schoolId;
        this.name = name;
        this.description = description;
        this.assessmentGroupId = assessmentGroupId;
        this.assessmentGroupName = assessmentGroupName;
        this.gradingOverride = gradingOverride;
        this.isDefault = isDefault;
        this.isActive = isActive;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.sections = sections;
        this.brandingJson = brandingJson;
    }

    public Long getId() { return id; }
    public Long getSchoolId() { return schoolId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public Long getAssessmentGroupId() { return assessmentGroupId; }
    public String getAssessmentGroupName() { return assessmentGroupName; }
    public String getGradingOverride() { return gradingOverride; }
    public Boolean getIsDefault() { return isDefault; }
    public Boolean getIsActive() { return isActive; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public List<SectionDTO> getSections() { return sections; }
    public String getBrandingJson() { return brandingJson; }
    public void setBrandingJson(String brandingJson) { this.brandingJson = brandingJson; }

    // ── Nested: SectionDTO ────────────────────────────────────────────────

    public static class SectionDTO {
        private Long id;
        private String sectionType;
        private Boolean enabled;
        private Integer displayOrder;
        private String configJson;

        public SectionDTO() {}

        public SectionDTO(Long id, String sectionType, Boolean enabled,
                          Integer displayOrder, String configJson) {
            this.id = id;
            this.sectionType = sectionType;
            this.enabled = enabled;
            this.displayOrder = displayOrder;
            this.configJson = configJson;
        }

        public Long getId() { return id; }
        public String getSectionType() { return sectionType; }
        public Boolean getEnabled() { return enabled; }
        public Integer getDisplayOrder() { return displayOrder; }
        public String getConfigJson() { return configJson; }
        public void setId(Long id) { this.id = id; }
        public void setSectionType(String sectionType) { this.sectionType = sectionType; }
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }
        public void setDisplayOrder(Integer displayOrder) { this.displayOrder = displayOrder; }
        public void setConfigJson(String configJson) { this.configJson = configJson; }
    }
}
