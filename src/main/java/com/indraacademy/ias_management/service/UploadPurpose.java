package com.indraacademy.ias_management.service;

import java.util.Set;

/**
 * Every kind of direct-to-object-storage upload this backend currently authorizes. Deliberately
 * a small, closed set — adding a new category means adding an enum constant plus its policy
 * below, never accepting an arbitrary client-supplied "purpose" string.
 *
 * <p>Phase 1 implements exactly one: {@link #TEACHER_PROFILE_PHOTO}. Other categories (student
 * photo, school logo, report-card header, notice/event attachments) remain on the existing
 * local-disk upload path until a later phase migrates them individually — see the Phase 1 audit
 * report for why this one was chosen first.
 */
public enum UploadPurpose {
    TEACHER_PROFILE_PHOTO(Set.of("image/jpeg", "image/png", "image/webp"), 5L * 1024 * 1024);

    private final Set<String> allowedContentTypes;
    private final long maxSizeBytes;

    UploadPurpose(Set<String> allowedContentTypes, long maxSizeBytes) {
        this.allowedContentTypes = allowedContentTypes;
        this.maxSizeBytes = maxSizeBytes;
    }

    public Set<String> getAllowedContentTypes() { return allowedContentTypes; }
    public long getMaxSizeBytes() { return maxSizeBytes; }

    public boolean allowsContentType(String contentType) {
        return contentType != null && allowedContentTypes.contains(contentType);
    }

    /** The object-key extension for a content type this purpose has already validated — never
     * derived from a client-supplied filename (see ObjectStorageService.buildObjectKey). Unlike
     * the legacy TeacherService.uploadPhoto pipeline (which resizes and always re-encodes to
     * .jpg), the object itself is stored exactly as the client uploaded it — no server-side
     * image processing happens in the direct-upload path — so the extension must reflect the
     * real validated content type, not a hardcoded assumption. */
    public static String extensionFor(String contentType) {
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> "jpg";
        };
    }
}
