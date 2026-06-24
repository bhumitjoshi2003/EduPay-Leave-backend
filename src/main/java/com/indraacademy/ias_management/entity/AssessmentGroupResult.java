package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Cached computed weighted result for one student in one assessment group.
 *  Invalidated and recomputed whenever marks change or weightage config changes. */
@Entity
@Table(name = "assessment_group_result")
@Data
public class AssessmentGroupResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "student_id", nullable = false, length = 50)
    private String studentId;

    @Column(name = "assessment_group_id", nullable = false)
    private Long assessmentGroupId;

    @Column(name = "session", nullable = false, length = 10)
    private String session;

    /** Final weighted percentage (e.g. 72.3500 = 72.35%). */
    @Column(name = "weighted_score", precision = 8, scale = 4)
    private BigDecimal weightedScore;

    @Column(name = "total_obtained", precision = 8, scale = 2)
    private BigDecimal totalObtained;

    @Column(name = "total_max", precision = 8, scale = 2)
    private BigDecimal totalMax;

    /** Competition rank within the class for this assessment group (1 = highest). */
    @Column(name = "rank_position")
    private Integer rankPosition;

    @Column(name = "computed_at")
    private LocalDateTime computedAt;

    public AssessmentGroupResult() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }

    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }

    public Long getAssessmentGroupId() { return assessmentGroupId; }
    public void setAssessmentGroupId(Long assessmentGroupId) { this.assessmentGroupId = assessmentGroupId; }

    public String getSession() { return session; }
    public void setSession(String session) { this.session = session; }

    public BigDecimal getWeightedScore() { return weightedScore; }
    public void setWeightedScore(BigDecimal weightedScore) { this.weightedScore = weightedScore; }

    public BigDecimal getTotalObtained() { return totalObtained; }
    public void setTotalObtained(BigDecimal totalObtained) { this.totalObtained = totalObtained; }

    public BigDecimal getTotalMax() { return totalMax; }
    public void setTotalMax(BigDecimal totalMax) { this.totalMax = totalMax; }

    public Integer getRankPosition() { return rankPosition; }
    public void setRankPosition(Integer rankPosition) { this.rankPosition = rankPosition; }

    public LocalDateTime getComputedAt() { return computedAt; }
    public void setComputedAt(LocalDateTime computedAt) { this.computedAt = computedAt; }
}
