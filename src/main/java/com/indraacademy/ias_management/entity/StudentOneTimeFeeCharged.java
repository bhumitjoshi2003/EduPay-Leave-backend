package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Cross-session dedup guard: records that a ONE_TIME fee head has already been charged
 * to a student, so StudentFees generation never re-charges it in a later session (unlike
 * ANNUAL heads, which legitimately recur every session). Mirrors the in-memory
 * billedFeeHeadIds pattern InvoiceGenerationService already uses for the same purpose on
 * the separate Invoice system.
 */
@Entity
@Table(name = "student_one_time_fee_charged",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_sotfc_student_feehead",
                columnNames = {"school_id", "student_id", "fee_head_id"}))
public class StudentOneTimeFeeCharged {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "student_id", nullable = false, length = 50)
    private String studentId;

    @Column(name = "fee_head_id", nullable = false)
    private Long feeHeadId;

    @CreationTimestamp
    @Column(name = "charged_at", nullable = false, updatable = false)
    private LocalDateTime chargedAt;

    public StudentOneTimeFeeCharged() {}

    public StudentOneTimeFeeCharged(Long schoolId, String studentId, Long feeHeadId) {
        this.schoolId = schoolId;
        this.studentId = studentId;
        this.feeHeadId = feeHeadId;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }

    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }

    public Long getFeeHeadId() { return feeHeadId; }
    public void setFeeHeadId(Long feeHeadId) { this.feeHeadId = feeHeadId; }

    public LocalDateTime getChargedAt() { return chargedAt; }
    public void setChargedAt(LocalDateTime chargedAt) { this.chargedAt = chargedAt; }
}
