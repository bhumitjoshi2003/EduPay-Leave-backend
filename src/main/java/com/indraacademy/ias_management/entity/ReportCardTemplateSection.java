package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "report_card_template_section")
@Data
public class ReportCardTemplateSection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    /** Section identifier — one of the known section types:
     *  SCHOOL_HEADER, STUDENT_INFO, MARKS_TABLE, ASSESSMENT_SUMMARY,
     *  ATTENDANCE, TEACHER_REMARKS, PRINCIPAL_REMARKS,
     *  CO_SCHOLASTIC, PROMOTION_STATUS, SIGNATURES */
    @Column(name = "section_type", nullable = false, length = 50)
    private String sectionType;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 0;

    /** JSON string with section-specific configuration.
     *  e.g. ATTENDANCE: {"showWorkingDays":true,"showPercentage":true}
     *       CO_SCHOLASTIC: {"activities":["Discipline","Sports"],"gradeScale":["A","B","C","D"]} */
    @Column(name = "config_json", columnDefinition = "TEXT")
    private String configJson;

    public ReportCardTemplateSection() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public String getSectionType() { return sectionType; }
    public void setSectionType(String sectionType) { this.sectionType = sectionType; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Integer getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(Integer displayOrder) { this.displayOrder = displayOrder; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
}
