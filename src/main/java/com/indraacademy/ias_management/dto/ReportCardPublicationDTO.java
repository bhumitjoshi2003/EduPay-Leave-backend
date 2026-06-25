package com.indraacademy.ias_management.dto;

public class ReportCardPublicationDTO {

    private boolean published;
    private Long    id;
    private Long    templateId;
    private String  templateName;
    private String  session;
    private String  className;
    private String  publishedAt;   // ISO-8601
    private String  publishedBy;
    private String  emailSentAt;   // ISO-8601 or null
    private Integer emailCount;

    public ReportCardPublicationDTO(boolean published, Long id, Long templateId,
                                    String templateName, String session, String className,
                                    String publishedAt, String publishedBy,
                                    String emailSentAt, Integer emailCount) {
        this.published    = published;
        this.id           = id;
        this.templateId   = templateId;
        this.templateName = templateName;
        this.session      = session;
        this.className    = className;
        this.publishedAt  = publishedAt;
        this.publishedBy  = publishedBy;
        this.emailSentAt  = emailSentAt;
        this.emailCount   = emailCount;
    }

    /** Convenience factory for the not-published case. */
    public static ReportCardPublicationDTO notPublished(Long templateId, String session, String className) {
        return new ReportCardPublicationDTO(false, null, templateId, null,
                session, className, null, null, null, 0);
    }

    public boolean isPublished()    { return published; }
    public Long    getId()          { return id; }
    public Long    getTemplateId()  { return templateId; }
    public String  getTemplateName(){ return templateName; }
    public String  getSession()     { return session; }
    public String  getClassName()   { return className; }
    public String  getPublishedAt() { return publishedAt; }
    public String  getPublishedBy() { return publishedBy; }
    public String  getEmailSentAt() { return emailSentAt; }
    public Integer getEmailCount()  { return emailCount; }
}
