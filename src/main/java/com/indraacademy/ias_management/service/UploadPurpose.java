package com.indraacademy.ias_management.service;

import java.util.Set;

/**
 * Every kind of direct-to-object-storage upload this backend currently authorizes. Deliberately
 * a small, closed set — adding a new category means adding an enum constant plus its policy
 * below, never accepting an arbitrary client-supplied "purpose" string.
 *
 * <p>Phase 1 implemented exactly one: {@link #TEACHER_PROFILE_PHOTO}. Phase 2 adds the remaining
 * "normal persistent user upload" categories — student/admin profile photos, school branding
 * (logo, report-card header), and event images. Knowledge-base documents are deliberately NOT
 * included here (see the Phase 2 audit report) — that subsystem stays on local disk for now.
 *
 * <p>Each constant drives {@link FileUploadRequestService}'s object-key construction via
 * {@link #entityType()}/{@link #objectKeySegment()}/{@link #schoolLevel()}, in addition to the
 * MIME/size policy every purpose has always declared. {@code entityType} is {@code null} exactly
 * when {@code schoolLevel} is true — there is no per-entity id below the school itself for those
 * two categories (see {@link com.indraacademy.ias_management.service.ObjectStorageService#buildSchoolLevelObjectKey}).
 */
public enum UploadPurpose {
    /** Unchanged from Phase 1 — same content types, same size, same object-key shape
     * (schools/{id}/teachers/{id}/profile/{uuid}.ext). */
    TEACHER_PROFILE_PHOTO(Set.of("image/jpeg", "image/png", "image/webp"), 5L * 1024 * 1024, "teachers", "profile", false),

    /** Tightened from the legacy StudentService.uploadPhoto's looser "any image/*, 10MB" policy
     * to the same explicit allow-list/5MB standard already established for teacher photos — the
     * old ceiling was never a deliberate business requirement, just legacy permissiveness. */
    STUDENT_PROFILE_PHOTO(Set.of("image/jpeg", "image/png", "image/webp"), 5L * 1024 * 1024, "students", "profile", false),

    /** Same tightening rationale as STUDENT_PROFILE_PHOTO — legacy AdminService.uploadPhoto also
     * allowed any image/* up to 10MB. */
    ADMIN_PROFILE_PHOTO(Set.of("image/jpeg", "image/png", "image/webp"), 5L * 1024 * 1024, "admins", "profile", false),

    /** School-level — one logo per school, not per some other entity id. Size limit matches the
     * legacy SchoolService.uploadLogo's existing 5MB cap (already an explicit image allow-list in
     * spirit, just tightened to jpeg/png/webp here for consistency). */
    SCHOOL_LOGO(Set.of("image/jpeg", "image/png", "image/webp"), 5L * 1024 * 1024, null, "logo", true),

    /** School-level. Size limit preserved from the legacy SchoolService.uploadReportCardHeader's
     * existing 10MB cap — a report-card header can reasonably be a higher-resolution print asset,
     * unlike a small profile photo. */
    REPORT_CARD_HEADER_IMAGE(Set.of("image/jpeg", "image/png", "image/webp"), 10L * 1024 * 1024, null, "report-card-header", true),

    /** Size limit preserved from the legacy FileStorageService.MAX_FILE_SIZE (event image via the
     * now-retired /api/files/uploadEventImage). image/gif is kept (unlike the other categories)
     * because the existing event-form frontend already deliberately allowed animated GIF event
     * banners — dropping it here would be a silent feature regression, not a security tightening.
     * entityId is either a real event id (replacing an existing event's image) or the literal
     * sentinel "new" (uploading an image before the event itself has been created) — see
     * FileUploadRequestService.authorizeForPurpose. */
    EVENT_IMAGE(Set.of("image/jpeg", "image/png", "image/webp", "image/gif"), 10L * 1024 * 1024, "events", "images", false),

    /** Optional screenshot on a technical support ticket. Always uploaded with entityId
     * {@link #NEW_EVENT_SENTINEL} — the ticket itself doesn't exist yet when the screenshot is
     * picked (mirrors EVENT_IMAGE's own "new" flow), and Phase 1 never replaces a screenshot on
     * an already-created ticket, so there is nothing to attach to at completeUpload time; the
     * resulting objectKey is instead included directly in SupportTicketDtos.CreateRequest. Any
     * authenticated user may use this purpose — see FileUploadRequestService.authorizeForPurpose. */
    SUPPORT_TICKET_SCREENSHOT(Set.of("image/jpeg", "image/png", "image/webp"), 5L * 1024 * 1024, "support-tickets", "screenshots", false),

    /** Optional single attachment (image or PDF) on a teacher's Homework/Classwork post. Always
     * uploaded with entityId {@link #NEW_EVENT_SENTINEL} before the post is saved; the service
     * verifies the resulting objectKey against its UploadIntent (same school, same teacher, this
     * purpose, COMPLETED) before persisting it. TEACHER only — see authorizeForPurpose. */
    HOMEWORK_ATTACHMENT(Set.of("image/jpeg", "image/png", "image/webp", "application/pdf"), 10L * 1024 * 1024, "homework", "attachments", false),

    /** Optional single attachment (image or PDF) on a teacher's Class Update. Always uploaded with
     * entityId {@link #NEW_EVENT_SENTINEL} before the update is saved; ClassUpdateService verifies
     * the resulting objectKey against its UploadIntent (same school, same teacher, this purpose,
     * COMPLETED) before persisting it. TEACHER only — see authorizeForPurpose. */
    CLASS_UPDATE_ATTACHMENT(Set.of("image/jpeg", "image/png", "image/webp", "application/pdf"), 10L * 1024 * 1024, "class-updates", "attachments", false),

    /** Optional single attachment (image or PDF) on an assessment. Always uploaded with entityId
     * {@link #NEW_EVENT_SENTINEL} before the assessment is saved; AssessmentService verifies the
     * resulting objectKey against its UploadIntent (same school, same uploader, this purpose,
     * COMPLETED) before persisting it. TEACHER and ADMIN only — see authorizeForPurpose. */
    ASSESSMENT_ATTACHMENT(Set.of("image/jpeg", "image/png", "image/webp", "application/pdf"), 10L * 1024 * 1024, "assessments", "attachments", false);

    /** Sentinel entityId for an EVENT_IMAGE, SUPPORT_TICKET_SCREENSHOT, HOMEWORK_ATTACHMENT,
     * CLASS_UPDATE_ATTACHMENT or ASSESSMENT_ATTACHMENT upload requested before the owning entity
     * itself exists yet. */
    public static final String NEW_EVENT_SENTINEL = "new";

    private final Set<String> allowedContentTypes;
    private final long maxSizeBytes;
    private final String entityType;
    private final String objectKeySegment;
    private final boolean schoolLevel;

    UploadPurpose(Set<String> allowedContentTypes, long maxSizeBytes, String entityType, String objectKeySegment, boolean schoolLevel) {
        this.allowedContentTypes = allowedContentTypes;
        this.maxSizeBytes = maxSizeBytes;
        this.entityType = entityType;
        this.objectKeySegment = objectKeySegment;
        this.schoolLevel = schoolLevel;
    }

    public Set<String> getAllowedContentTypes() { return allowedContentTypes; }
    public long getMaxSizeBytes() { return maxSizeBytes; }
    /** Null for a school-level purpose — see {@link #schoolLevel()}. */
    public String entityType() { return entityType; }
    public String objectKeySegment() { return objectKeySegment; }
    /** True when this purpose has no per-entity id below the school itself (logo, report-card
     * header) — see {@link ObjectStorageService#buildSchoolLevelObjectKey}. */
    public boolean schoolLevel() { return schoolLevel; }

    public boolean allowsContentType(String contentType) {
        return contentType != null && allowedContentTypes.contains(contentType);
    }

    /** The object-key extension for a content type this purpose has already validated — never
     * derived from a client-supplied filename (see ObjectStorageService.buildObjectKey). Unlike
     * the legacy resize-and-re-encode-to-.jpg pipelines this replaces, the object itself is
     * stored exactly as the client uploaded it — no server-side image processing happens in the
     * direct-upload path — so the extension must reflect the real validated content type, not a
     * hardcoded assumption. */
    public static String extensionFor(String contentType) {
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            case "application/pdf" -> "pdf";
            default -> "jpg";
        };
    }
}
