package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;

@Entity
@Table(name = "assessment_group_exam_mapping")
@Data
public class AssessmentGroupExamMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "assessment_group_id", nullable = false)
    private Long assessmentGroupId;

    @Column(name = "exam_config_id", nullable = false)
    private Long examConfigId;

    /** Fraction of total weight: 0.2000 = 20%, 0.8000 = 80%. Must sum to 1.0 per group. */
    @Column(name = "weightage", nullable = false, precision = 5, scale = 4)
    private BigDecimal weightage;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 0;

    public AssessmentGroupExamMapping() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }

    public Long getAssessmentGroupId() { return assessmentGroupId; }
    public void setAssessmentGroupId(Long assessmentGroupId) { this.assessmentGroupId = assessmentGroupId; }

    public Long getExamConfigId() { return examConfigId; }
    public void setExamConfigId(Long examConfigId) { this.examConfigId = examConfigId; }

    public BigDecimal getWeightage() { return weightage; }
    public void setWeightage(BigDecimal weightage) { this.weightage = weightage; }

    public Integer getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(Integer displayOrder) { this.displayOrder = displayOrder; }
}
