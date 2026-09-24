package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.BatchSize;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** A teacher's Homework and/or Classwork post for one timetable period on one date (V76, attachments V77). */
@Entity
@Table(name = "homework_classwork")
@Data
public class HomeworkClasswork {

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

    @Column(name = "subject_name", nullable = false)
    private String subjectName;

    @Column(name = "timetable_entry_id")
    private Long timetableEntryId;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(columnDefinition = "TEXT")
    private String classwork;

    @Column(columnDefinition = "TEXT")
    private String homework;

    @Column(name = "due_date")
    private LocalDate dueDate;

    /** Up to 5, in display order. Removed with the post (orphanRemoval + DB ON DELETE CASCADE). */
    @OneToMany(mappedBy = "homeworkClasswork", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, id ASC")
    @BatchSize(size = 50)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<HomeworkClassworkAttachment> attachments = new ArrayList<>();

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
