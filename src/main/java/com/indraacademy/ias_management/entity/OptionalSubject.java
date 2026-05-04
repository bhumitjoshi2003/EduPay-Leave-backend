package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "optional_subject")
@Data
public class OptionalSubject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id")
    private Long schoolId;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(name = "subject_name", nullable = false)
    private String subjectName;

    public OptionalSubject() {}

    public Long getId() { return id; }
    public Long getGroupId() { return groupId; }
    public String getSubjectName() { return subjectName; }

    public void setId(Long id) { this.id = id; }
    public void setGroupId(Long groupId) { this.groupId = groupId; }
    public void setSubjectName(String subjectName) { this.subjectName = subjectName; }

    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }
}
