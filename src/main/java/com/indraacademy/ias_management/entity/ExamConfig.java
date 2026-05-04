package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "exam_config",
        uniqueConstraints = @UniqueConstraint(columnNames = {"session", "class_name", "exam_name"}))
@Data
public class ExamConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id")
    private Long schoolId;

    @Column(name = "session", nullable = false)
    private String session;

    @Column(name = "class_name", nullable = false)
    private String className;

    @Column(name = "exam_name", nullable = false)
    private String examName;

    public ExamConfig() {}

    public Long getId() { return id; }
    public String getSession() { return session; }
    public String getClassName() { return className; }
    public String getExamName() { return examName; }

    public void setId(Long id) { this.id = id; }
    public void setSession(String session) { this.session = session; }
    public void setClassName(String className) { this.className = className; }
    public void setExamName(String examName) { this.examName = examName; }

    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }
}
