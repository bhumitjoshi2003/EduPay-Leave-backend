package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "report_card_publication",
       uniqueConstraints = @UniqueConstraint(name = "uq_pub",
           columnNames = {"school_id", "template_id", "session", "class_name"}))
public class ReportCardPublication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Column(name = "session", nullable = false, length = 20)
    private String session;

    @Column(name = "class_name", nullable = false, length = 50)
    private String className;

    @Column(name = "published_at", nullable = false)
    private LocalDateTime publishedAt = LocalDateTime.now();

    @Column(name = "published_by", length = 150)
    private String publishedBy;

    @Column(name = "email_sent_at")
    private LocalDateTime emailSentAt;

    @Column(name = "email_count", nullable = false)
    private Integer emailCount = 0;

    public Long getId()                          { return id; }
    public Long getSchoolId()                    { return schoolId; }
    public void setSchoolId(Long schoolId)       { this.schoolId = schoolId; }
    public Long getTemplateId()                  { return templateId; }
    public void setTemplateId(Long templateId)   { this.templateId = templateId; }
    public String getSession()                   { return session; }
    public void setSession(String session)       { this.session = session; }
    public String getClassName()                 { return className; }
    public void setClassName(String className)   { this.className = className; }
    public LocalDateTime getPublishedAt()                        { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt)        { this.publishedAt = publishedAt; }
    public String getPublishedBy()                               { return publishedBy; }
    public void setPublishedBy(String publishedBy)               { this.publishedBy = publishedBy; }
    public LocalDateTime getEmailSentAt()                        { return emailSentAt; }
    public void setEmailSentAt(LocalDateTime emailSentAt)        { this.emailSentAt = emailSentAt; }
    public Integer getEmailCount()                               { return emailCount; }
    public void setEmailCount(Integer emailCount)                { this.emailCount = emailCount; }
}
