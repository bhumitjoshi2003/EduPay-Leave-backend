package com.indraacademy.ias_management.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class HomeworkClassworkDtos {
    private HomeworkClassworkDtos() {}

    /**
     * A reference to an uploaded file. objectKey must be the teacher's own COMPLETED
     * HOMEWORK_ATTACHMENT upload (or, on edit, one already on the post); fileName is display-only.
     * Content type and size are taken from the verified upload, never from the client.
     */
    public record AttachmentRef(String objectKey, String fileName) {}

    /**
     * Class, section, subject and session are never taken from the client: they are derived from
     * the timetable entry, which is also what the teacher is authorized against. attachments is
     * the full ordered list (max 5).
     */
    public record CreateRequest(Long timetableEntryId, LocalDate workDate, String classwork, String homework,
                                LocalDate dueDate, List<AttachmentRef> attachments) {}

    /**
     * Content only — the period, class and date of a post cannot be changed after creation.
     * attachments replaces the post's attachment list (same order rules, max 5).
     */
    public record UpdateRequest(String classwork, String homework, LocalDate dueDate, List<AttachmentRef> attachments) {}

    /**
     * url is a short-lived presigned download URL; type is IMAGE or PDF. objectKey is only
     * returned to the post's own teacher (needed to keep an attachment on edit).
     */
    public record AttachmentView(long id, String fileName, String contentType, long fileSize, String type,
                                 String url, String objectKey) {}

    /** canEdit is true only for the post's own teacher. */
    public record WorkView(long id, LocalDate workDate, long classId, String className, Long sectionId,
                           String sectionName, String subjectName, String teacherId, String teacherName,
                           Long timetableEntryId, String classwork, String homework, LocalDate dueDate,
                           List<AttachmentView> attachments, LocalDateTime createdAt, LocalDateTime updatedAt,
                           boolean canEdit) {}
}
