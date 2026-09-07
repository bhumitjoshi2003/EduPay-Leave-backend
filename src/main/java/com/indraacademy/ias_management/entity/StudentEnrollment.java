package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "student_enrollment", indexes = {
        @Index(name = "idx_student_enrollment_student_session",
                columnList = "school_id, student_id, academic_session_id, effective_from"),
        @Index(name = "idx_student_enrollment_session_roster",
                columnList = "school_id, academic_session_id, class_id, section_id, status"),
        @Index(name = "idx_student_enrollment_effective_dates",
                columnList = "school_id, student_id, effective_from, effective_until")
})
public class StudentEnrollment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "student_id", nullable = false, length = 50)
    private String studentId;

    @Column(name = "academic_session_id", nullable = false)
    private Long academicSessionId;

    @Column(name = "class_id", nullable = false)
    private Long classId;

    @Column(name = "class_name_snapshot", nullable = false, length = 100)
    private String classNameSnapshot;

    @Column(name = "section_id")
    private Long sectionId;

    @Column(name = "section_name_snapshot", length = 50)
    private String sectionNameSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private StudentEnrollmentStatus status;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_until")
    private LocalDate effectiveUntil;

    @Enumerated(EnumType.STRING)
    @Column(name = "closure_reason", length = 30)
    private StudentEnrollmentClosureReason closureReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }
    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }
    public Long getAcademicSessionId() { return academicSessionId; }
    public void setAcademicSessionId(Long academicSessionId) { this.academicSessionId = academicSessionId; }
    public Long getClassId() { return classId; }
    public void setClassId(Long classId) { this.classId = classId; }
    public String getClassNameSnapshot() { return classNameSnapshot; }
    public void setClassNameSnapshot(String classNameSnapshot) { this.classNameSnapshot = classNameSnapshot; }
    public Long getSectionId() { return sectionId; }
    public void setSectionId(Long sectionId) { this.sectionId = sectionId; }
    public String getSectionNameSnapshot() { return sectionNameSnapshot; }
    public void setSectionNameSnapshot(String sectionNameSnapshot) { this.sectionNameSnapshot = sectionNameSnapshot; }
    public StudentEnrollmentStatus getStatus() { return status; }
    public void setStatus(StudentEnrollmentStatus status) { this.status = status; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(LocalDate effectiveFrom) { this.effectiveFrom = effectiveFrom; }
    public LocalDate getEffectiveUntil() { return effectiveUntil; }
    public void setEffectiveUntil(LocalDate effectiveUntil) { this.effectiveUntil = effectiveUntil; }
    public StudentEnrollmentClosureReason getClosureReason() { return closureReason; }
    public void setClosureReason(StudentEnrollmentClosureReason closureReason) { this.closureReason = closureReason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
