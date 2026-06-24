package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "fee_structure_rule",
        indexes = {
                @Index(name = "idx_fsr_school_session_class",
                        columnList = "school_id, academic_session_id, class_name")
        },
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_fsr_school_session_class_feehead",
                        columnNames = {"school_id", "academic_session_id", "class_name", "fee_head_id"})
        })
public class FeeStructureRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fee_head_id", nullable = false)
    private FeeHead feeHead;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "academic_session_id", nullable = false)
    private AcademicSession academicSession;

    @Column(name = "class_name", nullable = false, length = 50)
    private String className;

    @Column(name = "class_id")
    private Long classId;

    /** Amount in paise (INR). e.g. 150000 = Rs 1500.00 */
    @Column(name = "amount", nullable = false)
    private long amount;

    /** Rule is active from this date (inclusive). Allows mid-session fee changes. */
    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    /** Rule is active until this date (inclusive). Null = open-ended. */
    @Column(name = "effective_until")
    private LocalDate effectiveUntil;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public FeeStructureRule() {}

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }

    public FeeHead getFeeHead() { return feeHead; }
    public void setFeeHead(FeeHead feeHead) { this.feeHead = feeHead; }

    public AcademicSession getAcademicSession() { return academicSession; }
    public void setAcademicSession(AcademicSession academicSession) { this.academicSession = academicSession; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public Long getClassId() { return classId; }
    public void setClassId(Long classId) { this.classId = classId; }

    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }

    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(LocalDate effectiveFrom) { this.effectiveFrom = effectiveFrom; }

    public LocalDate getEffectiveUntil() { return effectiveUntil; }
    public void setEffectiveUntil(LocalDate effectiveUntil) { this.effectiveUntil = effectiveUntil; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
