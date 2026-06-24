package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;

@Entity
@Table(name = "assessment_group_composition")
@Data
public class AssessmentGroupComposition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    /** The parent GROUP_BASED group (e.g. Annual). */
    @Column(name = "parent_group_id", nullable = false)
    private Long parentGroupId;

    /** A child EXAM_BASED or GROUP_BASED group (e.g. Term 1, Term 2). */
    @Column(name = "child_group_id", nullable = false)
    private Long childGroupId;

    /** Fraction of total weight: 0.5000 = 50%. Must sum to 1.0 per parent group. */
    @Column(name = "weightage", nullable = false, precision = 5, scale = 4)
    private BigDecimal weightage;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 0;

    public AssessmentGroupComposition() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }

    public Long getParentGroupId() { return parentGroupId; }
    public void setParentGroupId(Long parentGroupId) { this.parentGroupId = parentGroupId; }

    public Long getChildGroupId() { return childGroupId; }
    public void setChildGroupId(Long childGroupId) { this.childGroupId = childGroupId; }

    public BigDecimal getWeightage() { return weightage; }
    public void setWeightage(BigDecimal weightage) { this.weightage = weightage; }

    public Integer getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(Integer displayOrder) { this.displayOrder = displayOrder; }
}
