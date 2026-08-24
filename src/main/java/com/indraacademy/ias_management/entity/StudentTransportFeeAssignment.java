package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "student_transport_fee_assignment")
@Data
public class StudentTransportFeeAssignment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "school_id", nullable = false)
    private Long schoolId;
    @Column(name = "student_id", nullable = false)
    private String studentId;
    @Column(name = "academic_session", nullable = false, length = 20)
    private String academicSession;
    @Column(nullable = false)
    private boolean enabled;
    private Double distance;
    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;
    @Column(name = "effective_to")
    private LocalDate effectiveTo;
    @Column(length = 500)
    private String reason;
    @Column(name = "changed_by")
    private String changedBy;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}

