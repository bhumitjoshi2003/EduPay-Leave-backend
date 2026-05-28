package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "class_subject",
        uniqueConstraints = @UniqueConstraint(columnNames = {"class_name", "subject_name"}))
@Data
public class ClassSubject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id")
    private Long schoolId;

    @Column(name = "class_name", nullable = false)
    private String className;

    @Column(name = "class_id")
    private Long classId;

    @Column(name = "subject_name", nullable = false)
    private String subjectName;

    /** True if this subject is an elective (students choose one from a group). */
    @Column(name = "optional", nullable = false)
    private boolean optional = false;

    /**
     * Logical group name for mutual-exclusion (e.g. "elective-1").
     * All subjects sharing the same optionalGroup are alternatives — a student picks exactly one.
     * Null when optional=false.
     */
    @Column(name = "optional_group")
    private String optionalGroup;

    public ClassSubject() {}

    public Long getId() { return id; }
    public String getClassName() { return className; }
    public String getSubjectName() { return subjectName; }
    public boolean isOptional() { return optional; }
    public String getOptionalGroup() { return optionalGroup; }

    public void setId(Long id) { this.id = id; }
    public void setClassName(String className) { this.className = className; }
    public void setSubjectName(String subjectName) { this.subjectName = subjectName; }
    public void setOptional(boolean optional) { this.optional = optional; }
    public void setOptionalGroup(String optionalGroup) { this.optionalGroup = optionalGroup; }

    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }
}
