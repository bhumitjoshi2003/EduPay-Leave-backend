package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Phase F4.1: the LATEST successful class-teacher activation for one (school, AcademicSession) —
 * not a revision history. Exists solely so {@code ClassTeacherActivationService} can answer
 * "was this session's current configuration ever explicitly applied, and is it still in sync"
 * without inferring it from audit logs or timestamps. Written only inside the same transaction
 * as the live Teacher.classTeacher/classTeacherSectionId writes it describes — see
 * {@code ClassTeacherActivationService#apply}.
 */
@Entity
@Table(name = "class_teacher_activation")
public class ClassTeacherActivation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "academic_session_id", nullable = false)
    private Long academicSessionId;

    /** SHA-256 hex digest of the canonical (classId, sectionId, teacherId) set that was actually
     *  applied — a content comparison, not a timestamp-based inference. */
    @Column(name = "configuration_fingerprint", nullable = false, length = 64)
    private String configurationFingerprint;

    @Column(name = "applied_at", nullable = false)
    private LocalDateTime appliedAt;

    @Column(name = "applied_by")
    private String appliedBy;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }

    public Long getAcademicSessionId() { return academicSessionId; }
    public void setAcademicSessionId(Long academicSessionId) { this.academicSessionId = academicSessionId; }

    public String getConfigurationFingerprint() { return configurationFingerprint; }
    public void setConfigurationFingerprint(String configurationFingerprint) { this.configurationFingerprint = configurationFingerprint; }

    public LocalDateTime getAppliedAt() { return appliedAt; }
    public void setAppliedAt(LocalDateTime appliedAt) { this.appliedAt = appliedAt; }

    public String getAppliedBy() { return appliedBy; }
    public void setAppliedBy(String appliedBy) { this.appliedBy = appliedBy; }
}
