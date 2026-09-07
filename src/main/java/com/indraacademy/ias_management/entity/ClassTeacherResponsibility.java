package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * A session-scoped, admin-configured record of which teacher is responsible for a class/section
 * in a given AcademicSession — historical/future CONFIGURATION only. This is deliberately
 * separate from {@link Teacher#getClassTeacher()}/{@link Teacher#getClassTeacherSectionId()},
 * the LIVE authorization projection {@code TeacherClassScopeService} reads: this table is never
 * consulted for authorization, and Phase F2 wires nothing here into that path.
 *
 * <p>No status/lifecycle column: a responsibility change is a plain update (or replace) of the
 * one row the DB-level uniqueness on (school, session, class[, section]) already guarantees is
 * singular — there is no state transition and nothing queries by a status, so one was not added.
 */
@Entity
@Table(name = "class_teacher_responsibility")
public class ClassTeacherResponsibility {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "academic_session_id", nullable = false)
    private Long academicSessionId;

    @Column(name = "class_id", nullable = false)
    private Long classId;

    /** Null when the class has no configured sections — same sectionless semantics already
     *  used throughout this codebase (e.g. TimetableEntry, StudentEnrollment). */
    @Column(name = "section_id")
    private Long sectionId;

    @Column(name = "teacher_id", nullable = false)
    private String teacherId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }

    public Long getAcademicSessionId() { return academicSessionId; }
    public void setAcademicSessionId(Long academicSessionId) { this.academicSessionId = academicSessionId; }

    public Long getClassId() { return classId; }
    public void setClassId(Long classId) { this.classId = classId; }

    public Long getSectionId() { return sectionId; }
    public void setSectionId(Long sectionId) { this.sectionId = sectionId; }

    public String getTeacherId() { return teacherId; }
    public void setTeacherId(String teacherId) { this.teacherId = teacherId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
