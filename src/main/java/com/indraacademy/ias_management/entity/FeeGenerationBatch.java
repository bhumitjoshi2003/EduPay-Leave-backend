package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "fee_generation_batch", indexes = @Index(name = "idx_fee_generation_batch_school_session",
        columnList = "school_id, academic_session, started_at"))
@Data
public class FeeGenerationBatch {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "school_id", nullable = false) private Long schoolId;
    @Column(name = "academic_session", nullable = false, length = 20) private String academicSession;
    @Column(name = "effective_date", nullable = false) private LocalDate effectiveDate;
    @Column(name = "selected_months", nullable = false, length = 40) private String selectedMonths;
    @Column(name = "requested_student_ids", nullable = false, columnDefinition = "TEXT") private String requestedStudentIds;
    @Column(name = "failed_student_ids", columnDefinition = "TEXT") private String failedStudentIds;
    @Column(name = "requested_students", nullable = false) private int requestedStudents;
    @Column(name = "successful_students", nullable = false) private int successfulStudents;
    @Column(name = "failed_students", nullable = false) private int failedStudents;
    @Column(name = "generated_months", nullable = false) private int generatedMonths;
    @Column(name = "skipped_months", nullable = false) private int skippedMonths;
    @Column(nullable = false, length = 20) private String status;
    @Column(name = "initiated_by", nullable = false, length = 100) private String initiatedBy;
    @Column(name = "retry_of_batch_id") private Long retryOfBatchId;
    @Column(name = "started_at", nullable = false) private LocalDateTime startedAt;
    @Column(name = "completed_at") private LocalDateTime completedAt;
}
