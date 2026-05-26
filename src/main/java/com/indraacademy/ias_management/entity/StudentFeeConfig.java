package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "student_fee_config", indexes = {
        @Index(name = "idx_sfc_student_session",
                columnList = "school_id, student_id, academic_session_id")
})
public class StudentFeeConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "student_id", nullable = false, length = 50)
    private String studentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fee_head_id", nullable = false)
    private FeeHead feeHead;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "academic_session_id", nullable = false)
    private AcademicSession academicSession;

    @Enumerated(EnumType.STRING)
    @Column(name = "config_type", nullable = false, length = 20)
    private FeeConfigType configType;

    /**
     * Meaning depends on configType:
     * DISCOUNT_PERCENT -> percentage (0-100)
     * DISCOUNT_FIXED -> amount in paise
     * WAIVER -> ignored (full waiver)
     * CUSTOM_AMOUNT -> new amount in paise
     * OPT_OUT -> ignored (student opts out of optional fee)
     */
    @Column(name = "value", precision = 15, scale = 2)
    private BigDecimal value;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "valid_from")
    private LocalDate validFrom;

    @Column(name = "valid_until")
    private LocalDate validUntil;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public StudentFeeConfig() {}

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }

    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }

    public FeeHead getFeeHead() { return feeHead; }
    public void setFeeHead(FeeHead feeHead) { this.feeHead = feeHead; }

    public AcademicSession getAcademicSession() { return academicSession; }
    public void setAcademicSession(AcademicSession academicSession) { this.academicSession = academicSession; }

    public FeeConfigType getConfigType() { return configType; }
    public void setConfigType(FeeConfigType configType) { this.configType = configType; }

    public BigDecimal getValue() { return value; }
    public void setValue(BigDecimal value) { this.value = value; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getApprovedBy() { return approvedBy; }
    public void setApprovedBy(String approvedBy) { this.approvedBy = approvedBy; }

    public LocalDate getValidFrom() { return validFrom; }
    public void setValidFrom(LocalDate validFrom) { this.validFrom = validFrom; }

    public LocalDate getValidUntil() { return validUntil; }
    public void setValidUntil(LocalDate validUntil) { this.validUntil = validUntil; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
