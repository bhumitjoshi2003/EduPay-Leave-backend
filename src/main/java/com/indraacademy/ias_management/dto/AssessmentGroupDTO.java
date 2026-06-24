package com.indraacademy.ias_management.dto;

import java.math.BigDecimal;
import java.util.List;

/** Full response DTO for an AssessmentGroup, including its exam mappings or compositions. */
public class AssessmentGroupDTO {

    private Long id;
    private String session;
    private String className;
    private String name;
    private String displayName;
    private String groupType;
    private Integer displayOrder;
    private List<ExamMappingDTO> examMappings;
    private List<CompositionDTO> compositions;

    public AssessmentGroupDTO(Long id, String session, String className, String name,
                               String displayName, String groupType, Integer displayOrder,
                               List<ExamMappingDTO> examMappings, List<CompositionDTO> compositions) {
        this.id = id;
        this.session = session;
        this.className = className;
        this.name = name;
        this.displayName = displayName;
        this.groupType = groupType;
        this.displayOrder = displayOrder;
        this.examMappings = examMappings;
        this.compositions = compositions;
    }

    public Long getId() { return id; }
    public String getSession() { return session; }
    public String getClassName() { return className; }
    public String getName() { return name; }
    public String getDisplayName() { return displayName; }
    public String getGroupType() { return groupType; }
    public Integer getDisplayOrder() { return displayOrder; }
    public List<ExamMappingDTO> getExamMappings() { return examMappings; }
    public List<CompositionDTO> getCompositions() { return compositions; }

    // ── Nested: ExamMappingDTO ──────────────────────────────────────────

    public static class ExamMappingDTO {
        private Long id;
        private Long examConfigId;
        private String examName;
        private BigDecimal weightage;
        private Integer displayOrder;

        public ExamMappingDTO(Long id, Long examConfigId, String examName,
                              BigDecimal weightage, Integer displayOrder) {
            this.id = id;
            this.examConfigId = examConfigId;
            this.examName = examName;
            this.weightage = weightage;
            this.displayOrder = displayOrder;
        }

        public Long getId() { return id; }
        public Long getExamConfigId() { return examConfigId; }
        public String getExamName() { return examName; }
        public BigDecimal getWeightage() { return weightage; }
        public Integer getDisplayOrder() { return displayOrder; }
    }

    // ── Nested: CompositionDTO ──────────────────────────────────────────

    public static class CompositionDTO {
        private Long id;
        private Long childGroupId;
        private String childGroupName;
        private BigDecimal weightage;
        private Integer displayOrder;

        public CompositionDTO(Long id, Long childGroupId, String childGroupName,
                              BigDecimal weightage, Integer displayOrder) {
            this.id = id;
            this.childGroupId = childGroupId;
            this.childGroupName = childGroupName;
            this.weightage = weightage;
            this.displayOrder = displayOrder;
        }

        public Long getId() { return id; }
        public Long getChildGroupId() { return childGroupId; }
        public String getChildGroupName() { return childGroupName; }
        public BigDecimal getWeightage() { return weightage; }
        public Integer getDisplayOrder() { return displayOrder; }
    }
}
