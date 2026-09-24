package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDateTime;

/** A teacher's academic update for one class/section they teach (V78). */
@Entity
@Table(name = "class_update")
@Data
public class ClassUpdate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private long revision;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "academic_session_id", nullable = false)
    private Long academicSessionId;

    @Column(name = "teacher_id", nullable = false)
    private String teacherId;

    @Column(name = "teacher_name")
    private String teacherName;

    @Column(name = "class_id", nullable = false)
    private Long classId;

    @Column(name = "class_name", nullable = false)
    private String className;

    @Column(name = "section_id")
    private Long sectionId;

    @Column(name = "section_name", length = 50)
    private String sectionName;

    @Column(name = "subject_name")
    private String subjectName;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "attachment_object_key", unique = true, length = 500)
    private String attachmentObjectKey;

    @Column(name = "attachment_file_name")
    private String attachmentFileName;

    @Column(name = "attachment_content_type", length = 100)
    private String attachmentContentType;

    @Column(name = "attachment_file_size")
    private Long attachmentFileSize;

    @Column(name = "expires_at")
    private Instant expiresAt;

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
