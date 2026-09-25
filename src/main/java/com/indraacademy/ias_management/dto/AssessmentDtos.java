package com.indraacademy.ias_management.dto;

import com.indraacademy.ias_management.entity.AssessmentType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public final class AssessmentDtos {
    private AssessmentDtos() {}

    /**
     * A reference to an uploaded file. objectKey must be the caller's own COMPLETED
     * ASSESSMENT_ATTACHMENT upload (or, on edit, the one already on the assessment); fileName is
     * display-only. Content type and size come from the verified upload, never from the client.
     */
    public record AttachmentRef(String objectKey, String fileName) {}

    /** A section the caller may target (sectionId null = the whole class) and its subjects. */
    public record ContextSection(Long sectionId, String sectionName, List<String> subjects) {}

    /** A class the caller may target, with the sections (and subjects) allowed within it. */
    public record ContextClass(long classId, String className, List<ContextSection> sections) {}

    /**
     * classId/sectionId/subjectName must be one of the caller's contexts; school, session and the
     * stored class/section names are resolved on the server, never taken from the client.
     */
    public record CreateRequest(AssessmentType assessmentType, String title, Long classId, Long sectionId,
                                String subjectName, LocalDate assessmentDate, LocalTime startTime,
                                LocalTime endTime, String syllabus, String instructions, AttachmentRef attachment) {}

    /** Everything except the class/section/subject, which cannot change after creation. */
    public record UpdateRequest(AssessmentType assessmentType, String title, LocalDate assessmentDate,
                                LocalTime startTime, LocalTime endTime, String syllabus, String instructions,
                                AttachmentRef attachment) {}

    /** url is a short-lived presigned download URL; objectKey is only returned to editors. */
    public record AttachmentView(String fileName, String contentType, long fileSize, String type, String url,
                                 String objectKey) {}

    /**
     * Effective permissions, always computed on the server: canEdit/canDelete for the creator or
     * an ADMIN of the same school (relevant teachers are read-only); createdByCurrentUser marks the
     * caller's own assessments. createdByName/createdByRole identify the source for the UI.
     */
    public record AssessmentView(long id, AssessmentType assessmentType, String typeLabel, String title,
                                 long classId, String className, Long sectionId, String sectionName,
                                 String subjectName, LocalDate assessmentDate, LocalTime startTime,
                                 LocalTime endTime, String syllabus, String instructions,
                                 AttachmentView attachment, String createdByUserId, String createdByName,
                                 String createdByRole, LocalDateTime createdAt, LocalDateTime updatedAt,
                                 boolean canEdit, boolean canDelete, boolean createdByCurrentUser) {}
}
