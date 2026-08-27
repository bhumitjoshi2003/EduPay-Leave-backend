package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "student_fee_assignment", uniqueConstraints = @UniqueConstraint(
        columnNames = {"school_id", "student_id", "academic_session"}))
@Data
public class StudentFeeAssignment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "school_id", nullable = false)
    private Long schoolId;
    @Column(name = "student_id", nullable = false)
    private String studentId;
    @Column(name = "academic_session", nullable = false, length = 20)
    private String academicSession;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private StudentFeeAssignmentStatus status = StudentFeeAssignmentStatus.NOT_ASSIGNED;
    @Column(name = "effective_date")
    private LocalDate effectiveDate;
    @Column(name = "selected_months", length = 40)
    private String selectedMonths;
    @Column(nullable = false)
    private boolean excluded;
    @Column(name = "exclusion_reason", length = 500)
    private String exclusionReason;
    @Column(name = "failure_reason", length = 1000)
    private String failureReason;
    @Column(name = "assigned_by")
    private String assignedBy;
    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;
    @Column(name = "generated_at")
    private LocalDateTime generatedAt;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

