package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "report_card_remark")
public class ReportCardRemark {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "student_id", nullable = false, length = 30)
    private String studentId;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Column(name = "session", nullable = false, length = 20)
    private String session;

    /** TEACHER or PRINCIPAL */
    @Column(name = "remark_type", nullable = false, length = 20)
    private String remarkType;

    @Column(name = "remark_text", columnDefinition = "TEXT")
    private String remarkText;

    @Column(name = "entered_by", length = 100)
    private String enteredBy;

    @Column(name = "entered_at", nullable = false)
    private LocalDateTime enteredAt = LocalDateTime.now();

    public ReportCardRemark() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }

    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }

    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }

    public String getSession() { return session; }
    public void setSession(String session) { this.session = session; }

    public String getRemarkType() { return remarkType; }
    public void setRemarkType(String remarkType) { this.remarkType = remarkType; }

    public String getRemarkText() { return remarkText; }
    public void setRemarkText(String remarkText) { this.remarkText = remarkText; }

    public String getEnteredBy() { return enteredBy; }
    public void setEnteredBy(String enteredBy) { this.enteredBy = enteredBy; }

    public LocalDateTime getEnteredAt() { return enteredAt; }
    public void setEnteredAt(LocalDateTime enteredAt) { this.enteredAt = enteredAt; }
}
