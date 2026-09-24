package com.indraacademy.ias_management.dto;

import java.time.Instant;
import java.time.LocalDateTime;

public final class ClassUpdateDtos {
    private ClassUpdateDtos() {}

    /**
     * A reference to an uploaded file. objectKey must be the teacher's own COMPLETED
     * CLASS_UPDATE_ATTACHMENT upload (or, on edit, the one already on the update); fileName is
     * display-only. Content type and size are taken from the verified upload, never from the client.
     */
    public record AttachmentRef(String objectKey, String fileName) {}

    /**
     * A class/section (and subject) the teacher teaches in the current session, from their own
     * timetable entries. Sent back as-is when creating an update.
     */
    public record TeachingContext(long classId, String className, Long sectionId, String sectionName,
                                  String subjectName) {}

    /**
     * classId/sectionId/subjectName must match one of the teacher's own timetable entries in the
     * current session; the stored names are taken from that entry, never from the client.
     * subjectName may be null for a class-wide (not subject-specific) update.
     */
    public record CreateRequest(Long classId, Long sectionId, String subjectName, String title, String message,
                                AttachmentRef attachment, Instant expiresAt) {}

    /** Content only — the class/section/subject of an update cannot be changed after creation. */
    public record UpdateRequest(String title, String message, AttachmentRef attachment, Instant expiresAt) {}

    /**
     * url is a short-lived presigned download URL; type is IMAGE or PDF. objectKey is only
     * returned to the update's own teacher (needed to keep the attachment on edit).
     */
    public record AttachmentView(String fileName, String contentType, long fileSize, String type, String url,
                                 String objectKey) {}

    /** canEdit is true only for the update's own teacher; expired once expiresAt has passed. */
    public record UpdateView(long id, long classId, String className, Long sectionId, String sectionName,
                             String subjectName, String teacherId, String teacherName, String title, String message,
                             AttachmentView attachment, Instant expiresAt, boolean expired,
                             LocalDateTime createdAt, LocalDateTime updatedAt, boolean canEdit) {}
}
