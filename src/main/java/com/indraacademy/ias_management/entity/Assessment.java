package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** A scheduled assessment for one class (optionally one section) and subject (V79). */
@Entity
@Table(name = "assessment")
@Data
public class Assessment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private long revision;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "academic_session_id", nullable = false)
    private Long academicSessionId;

    @Column(name = "created_by_user_id", nullable = false)
    private String createdByUserId;

    /** TEACHER or ADMIN. */
    @Column(name = "created_by_role", nullable = false, length = 20)
    private String createdByRole;

    @Column(name = "created_by_name")
    private String createdByName;

    @Enumerated(EnumType.STRING)
    @Column(name = "assessment_type", nullable = false, length = 30)
    private AssessmentType assessmentType;

    @Column(name = "class_id", nullable = false)
    private Long classId;

    @Column(name = "class_name", nullable = false)
    private String className;

    @Column(name = "section_id")
    private Long sectionId;

    @Column(name = "section_name", length = 50)
    private String sectionName;

    @Column(name = "subject_name", nullable = false)
    private String subjectName;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(name = "assessment_date", nullable = false)
    private LocalDate assessmentDate;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String syllabus;

    @Column(columnDefinition = "TEXT")
    private String instructions;

    @Column(name = "attachment_object_key", unique = true, length = 500)
    private String attachmentObjectKey;

    @Column(name = "attachment_file_name")
    private String attachmentFileName;

    @Column(name = "attachment_content_type", length = 100)
    private String attachmentContentType;

    @Column(name = "attachment_file_size")
    private Long attachmentFileSize;

    @Column(name = "reminder_sent_at")
    private Instant reminderSentAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

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
