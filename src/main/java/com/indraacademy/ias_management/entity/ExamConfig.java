package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "exam_config",
        uniqueConstraints = @UniqueConstraint(columnNames = {"school_id", "session", "class_name", "exam_name"}))
@Data
public class ExamConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "session", nullable = false)
    private String session;

    @Column(name = "class_name", nullable = false)
    private String className;

    @Column(name = "class_id")
    private Long classId;

    @Column(name = "exam_name", nullable = false)
    private String examName;

    /** DRAFT until an admin publishes; students/parents only ever see PUBLISHED results (V82). */
    @Enumerated(EnumType.STRING)
    @Column(name = "result_status", nullable = false, length = 20)
    private ExamResultStatus resultStatus = ExamResultStatus.DRAFT;

    @Column(name = "published_at")
    private java.time.LocalDateTime publishedAt;

    @Column(name = "published_by")
    private String publishedBy;

    @Version
    private long revision;

    public boolean isPublished() { return resultStatus == ExamResultStatus.PUBLISHED; }

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
