package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One student's marks for one exam subject (V82: FK to exam_subject_entry, created/updated
 * metadata and an optimistic-lock revision). marks_obtained is never null once saved — a subject
 * with no row is "not entered", never an implicit zero.
 */
@Entity
@Table(name = "student_mark",
        uniqueConstraints = @UniqueConstraint(columnNames = {"student_id", "exam_subject_entry_id"}))
@Data
public class StudentMark {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private long revision;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "student_id", nullable = false)
    private String studentId;

    @Column(name = "exam_subject_entry_id", nullable = false)
    private Long examSubjectEntryId;

    @Column(name = "marks_obtained")
    private Double marksObtained;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_by")
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public StudentMark() {}

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
