package com.indraacademy.ias_management.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

/** Request body for creating or updating an AssessmentGroup.
 *  Includes exam mappings (EXAM_BASED) or child group compositions (GROUP_BASED) inline. */
public class AssessmentGroupRequest {

    @NotBlank(message = "Session is required")
    private String session;

    @NotBlank(message = "Class name is required")
    private String className;

    @NotBlank(message = "Group name is required")
    private String name;

    private String displayName;

    @NotNull(message = "Group type is required")
    @Pattern(regexp = "EXAM_BASED|GROUP_BASED", message = "groupType must be EXAM_BASED or GROUP_BASED")
    private String groupType;

    private Integer displayOrder = 0;

    /** Populated when groupType = EXAM_BASED. */
    private List<ExamMappingItem> examMappings;

    /** Populated when groupType = GROUP_BASED. */
    private List<CompositionItem> compositions;

    // ── Getters / Setters ──────────────────────────────────────────────

    public String getSession() { return session; }
    public void setSession(String session) { this.session = session; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getGroupType() { return groupType; }
    public void setGroupType(String groupType) { this.groupType = groupType; }

    public Integer getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(Integer displayOrder) { this.displayOrder = displayOrder; }

    public List<ExamMappingItem> getExamMappings() { return examMappings; }
    public void setExamMappings(List<ExamMappingItem> examMappings) { this.examMappings = examMappings; }

    public List<CompositionItem> getCompositions() { return compositions; }
    public void setCompositions(List<CompositionItem> compositions) { this.compositions = compositions; }

    // ── Nested: ExamMappingItem ────────────────────────────────────────

    public static class ExamMappingItem {

        @NotNull(message = "examConfigId is required")
        private Long examConfigId;

        @NotNull(message = "weightage is required")
        @DecimalMin(value = "0.0001", message = "weightage must be greater than 0")
        @DecimalMax(value = "1.0000", message = "weightage must not exceed 1.0")
        private BigDecimal weightage;

        private Integer displayOrder = 0;

        public Long getExamConfigId() { return examConfigId; }
        public void setExamConfigId(Long examConfigId) { this.examConfigId = examConfigId; }

        public BigDecimal getWeightage() { return weightage; }
        public void setWeightage(BigDecimal weightage) { this.weightage = weightage; }

        public Integer getDisplayOrder() { return displayOrder; }
        public void setDisplayOrder(Integer displayOrder) { this.displayOrder = displayOrder; }
    }

    // ── Nested: CompositionItem ────────────────────────────────────────

    public static class CompositionItem {

        @NotNull(message = "childGroupId is required")
        private Long childGroupId;

        @NotNull(message = "weightage is required")
        @DecimalMin(value = "0.0001", message = "weightage must be greater than 0")
        @DecimalMax(value = "1.0000", message = "weightage must not exceed 1.0")
        private BigDecimal weightage;

        private Integer displayOrder = 0;

        public Long getChildGroupId() { return childGroupId; }
        public void setChildGroupId(Long childGroupId) { this.childGroupId = childGroupId; }

        public BigDecimal getWeightage() { return weightage; }
        public void setWeightage(BigDecimal weightage) { this.weightage = weightage; }

        public Integer getDisplayOrder() { return displayOrder; }
        public void setDisplayOrder(Integer displayOrder) { this.displayOrder = displayOrder; }
    }
}
