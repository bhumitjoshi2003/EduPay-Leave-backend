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

    @Column(name = "subject_name", nullable = false)
    private String subjectName;

    public ClassSubject() {}

    public Long getId() { return id; }
    public String getClassName() { return className; }
    public String getSubjectName() { return subjectName; }

    public void setId(Long id) { this.id = id; }
    public void setClassName(String className) { this.className = className; }
    public void setSubjectName(String subjectName) { this.subjectName = subjectName; }

    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }
}
