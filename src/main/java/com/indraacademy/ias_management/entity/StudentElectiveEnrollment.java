package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;

/**
 * Records which elective subject a student is enrolled in for a given optional group.
 * The unique constraint ensures each student has at most one choice per group per class.
 */
@Entity
@Table(name = "student_elective_enrollment",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"school_id", "student_id", "class_name", "optional_group"}))
public class StudentElectiveEnrollment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "student_id", nullable = false)
    private String studentId;

    @Column(name = "class_name", nullable = false)
    private String className;

    /** The specific subject this student chose within the optional group. */
    @Column(name = "subject_name", nullable = false)
    private String subjectName;

    /** Matches ClassSubject.optionalGroup — groups mutually exclusive electives. */
    @Column(name = "optional_group", nullable = false)
    private String optionalGroup;

    public StudentElectiveEnrollment() {}

    public Long getId() { return id; }
    public Long getSchoolId() { return schoolId; }
    public String getStudentId() { return studentId; }
    public String getClassName() { return className; }
    public String getSubjectName() { return subjectName; }
    public String getOptionalGroup() { return optionalGroup; }

    public void setId(Long id) { this.id = id; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }
    public void setClassName(String className) { this.className = className; }
    public void setSubjectName(String subjectName) { this.subjectName = subjectName; }
    public void setOptionalGroup(String optionalGroup) { this.optionalGroup = optionalGroup; }
}
