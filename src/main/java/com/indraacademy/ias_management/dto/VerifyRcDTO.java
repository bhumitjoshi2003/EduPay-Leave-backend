package com.indraacademy.ias_management.dto;

/**
 * Public response for report-card QR verification.
 * Returned by GET /api/public/verify-rc?token={uuid} — no authentication required.
 * Contains only non-sensitive publication metadata (no marks, no personal data).
 */
public class VerifyRcDTO {

    private boolean valid;
    private String  schoolName;
    private String  className;
    private String  session;
    private String  publishedAt;   // ISO date string
    private String  publishedBy;
    private String  message;       // shown when valid=false

    public VerifyRcDTO() {}

    public static VerifyRcDTO valid(String schoolName, String className,
                                    String session, String publishedAt, String publishedBy) {
        VerifyRcDTO dto = new VerifyRcDTO();
        dto.valid       = true;
        dto.schoolName  = schoolName;
        dto.className   = className;
        dto.session     = session;
        dto.publishedAt = publishedAt;
        dto.publishedBy = publishedBy;
        return dto;
    }

    public static VerifyRcDTO invalid(String message) {
        VerifyRcDTO dto = new VerifyRcDTO();
        dto.valid   = false;
        dto.message = message;
        return dto;
    }

    public boolean isValid()        { return valid; }
    public String getSchoolName()   { return schoolName; }
    public String getClassName()    { return className; }
    public String getSession()      { return session; }
    public String getPublishedAt()  { return publishedAt; }
    public String getPublishedBy()  { return publishedBy; }
    public String getMessage()      { return message; }
}
