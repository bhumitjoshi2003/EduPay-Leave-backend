package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.ClassUpdateDtos.AttachmentRef;
import com.indraacademy.ias_management.dto.ClassUpdateDtos.AttachmentView;
import com.indraacademy.ias_management.dto.ClassUpdateDtos.CreateRequest;
import com.indraacademy.ias_management.dto.ClassUpdateDtos.TeachingContext;
import com.indraacademy.ias_management.dto.ClassUpdateDtos.UpdateRequest;
import com.indraacademy.ias_management.dto.ClassUpdateDtos.UpdateView;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Class Updates (Phase 1). A teacher posts an academic update to a class/section they teach
 * (their own timetable entries) in the school's current session; students see non-expired
 * updates for the class/section they are actively enrolled in. Only the posting teacher can edit
 * or delete. Notifies the class once, on create only.
 */
@Service
public class ClassUpdateService {
    static final int MAX_TITLE_LENGTH = 150;
    static final int MAX_MESSAGE_LENGTH = 5000;
    static final int MAX_FILE_NAME_LENGTH = 255;
    static final int TEACHER_RECENT_LIMIT = 30;
    static final int STUDENT_DEFAULT_LIMIT = 50;
    static final Duration MAX_EXPIRY = Duration.ofDays(366);

    private final ClassUpdateRepository updates;
    private final TimetableRepository timetable;
    private final TeacherRepository teachers;
    private final StudentEnrollmentRepository enrollments;
    private final TimetableSessionAccessService sessions;
    private final UploadIntentRepository uploadIntents;
    private final ObjectStorageService objectStorage;
    private final SecurityUtil security;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ClassUpdateService(ClassUpdateRepository updates, TimetableRepository timetable, TeacherRepository teachers,
                              StudentEnrollmentRepository enrollments, TimetableSessionAccessService sessions,
                              UploadIntentRepository uploadIntents, ObjectStorageService objectStorage,
                              SecurityUtil security, ApplicationEventPublisher events, Clock clock) {
        this.updates = updates;
        this.timetable = timetable;
        this.teachers = teachers;
        this.enrollments = enrollments;
        this.sessions = sessions;
        this.uploadIntents = uploadIntents;
        this.objectStorage = objectStorage;
        this.security = security;
        this.events = events;
        this.clock = clock;
    }

    // ─── Teacher ────────────────────────────────────────────────────────

    /** Distinct class/section/subject combinations from the teacher's own current-session timetable. */
    @Transactional(readOnly = true)
    public List<TeachingContext> myContexts() {
        Long schoolId = requireSchool();
        AcademicSession session = sessions.currentSessionOrNull(schoolId);
        if (session == null) return List.of();
        Map<String, TeachingContext> distinct = new LinkedHashMap<>();
        for (TimetableEntry e : timetable.findByAcademicSessionIdAndTeacherIdAndSchoolId(session.getId(),
                security.getUsername(), schoolId)) {
            if (e.getClassId() == null) continue;
            String subject = blankToNull(e.getSubjectName());
            distinct.putIfAbsent(e.getClassId() + ":" + e.getSectionId() + ":" + (subject == null ? "" : subject.toLowerCase(Locale.ROOT)),
                    new TeachingContext(e.getClassId(), e.getClassName(), e.getSectionId(), e.getSectionName(), subject));
        }
        return distinct.values().stream()
                .sorted(Comparator.comparing(TeachingContext::className, Comparator.nullsLast(ClassUpdateService::compareClassNames))
                        .thenComparing(TeachingContext::sectionName, Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(TeachingContext::subjectName, Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    @Transactional
    public UpdateView create(CreateRequest request) {
        Long schoolId = requireSchool();
        String teacherId = security.getUsername();
        if (request.classId() == null) throw new IllegalArgumentException("Choose a class.");

        AcademicSession session = sessions.requireCurrentSessionForTeacherWrite(schoolId);
        TimetableEntry entry = teachingEntry(schoolId, session.getId(), teacherId, request.classId(),
                request.sectionId(), blankToNull(request.subjectName()))
                .orElseThrow(() -> new AccessDeniedException("You can only post updates to classes you teach."));

        Content content = validatedContent(request.title(), request.message());
        Instant expiresAt = validatedExpiry(request.expiresAt(), null);

        ClassUpdate update = new ClassUpdate();
        update.setSchoolId(schoolId);
        update.setAcademicSessionId(session.getId());
        update.setTeacherId(teacherId);
        update.setTeacherName(teachers.findByTeacherIdAndSchoolId(teacherId, schoolId).map(Teacher::getName).orElse(null));
        update.setClassId(entry.getClassId());
        update.setClassName(entry.getClassName());
        update.setSectionId(entry.getSectionId());
        update.setSectionName(entry.getSectionName());
        update.setSubjectName(blankToNull(request.subjectName()) == null ? null : entry.getSubjectName());
        update.setTitle(content.title());
        update.setMessage(content.message());
        update.setExpiresAt(expiresAt);
        applyAttachment(update, request.attachment(), schoolId, teacherId);
        update = save(update);

        events.publishEvent(new ClassUpdatePostedEvent(update.getId(), schoolId, session.getId(), update.getClassId(),
                update.getSectionId(), update.getClassName(), update.getSectionName(), update.getSubjectName(), teacherId));
        return toView(update, true);
    }

    /** Content-only edit by the posting teacher. Deliberately does not notify students (Phase 1). */
    @Transactional
    public UpdateView update(long id, UpdateRequest request) {
        ClassUpdate update = ownUpdate(id);
        Content content = validatedContent(request.title(), request.message());
        update.setExpiresAt(validatedExpiry(request.expiresAt(), update.getExpiresAt()));
        update.setTitle(content.title());
        update.setMessage(content.message());
        applyAttachment(update, request.attachment(), update.getSchoolId(), update.getTeacherId());
        return toView(save(update), true);
    }

    /** Deletion by the posting teacher. Does not notify students. */
    @Transactional
    public void delete(long id) {
        updates.delete(ownUpdate(id));
    }

    /** The teacher's own recent updates, newest first, including expired ones. */
    @Transactional(readOnly = true)
    public List<UpdateView> myRecent() {
        Long schoolId = requireSchool();
        return updates.findBySchoolIdAndTeacherIdOrderByCreatedAtDescIdDesc(schoolId, security.getUsername(),
                PageRequest.of(0, TEACHER_RECENT_LIMIT)).stream().map(u -> toView(u, true)).toList();
    }

    // ─── Student ────────────────────────────────────────────────────────

    /** Non-expired updates for the student's own class/section, newest first. */
    @Transactional(readOnly = true)
    public List<UpdateView> studentActive(Integer limit) {
        int size = limit == null ? STUDENT_DEFAULT_LIMIT : Math.max(1, Math.min(limit, STUDENT_DEFAULT_LIMIT));
        Instant now = clock.instant();
        return studentScope().map(s -> updates.findActiveForClass(s.schoolId, s.sessionId, s.classId, s.sectionId, now,
                PageRequest.of(0, size)).stream().map(u -> toView(u, false)).toList()).orElse(List.of());
    }

    // ─── Single update (teacher owner, student in that class, school ADMIN/SUB_ADMIN read-only) ──

    @Transactional(readOnly = true)
    public UpdateView get(long id) {
        Long schoolId = requireSchool();
        ClassUpdate update = updates.findByIdAndSchoolId(id, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Update not found."));
        String role = security.getRole();
        if (Role.TEACHER.equals(role)) {
            if (!update.getTeacherId().equals(security.getUsername())) throw new NoSuchElementException("Update not found.");
            return toView(update, true);
        }
        if (Role.STUDENT.equals(role)) {
            StudentScope scope = studentScope().orElseThrow(() -> new NoSuchElementException("Update not found."));
            boolean visible = update.getAcademicSessionId().equals(scope.sessionId) && update.getClassId().equals(scope.classId)
                    && (update.getSectionId() == null || update.getSectionId().equals(scope.sectionId))
                    && !isExpired(update);
            if (!visible) throw new NoSuchElementException("Update not found.");
            return toView(update, false);
        }
        if (Role.ADMIN.equals(role) || Role.SUB_ADMIN.equals(role)) return toView(update, false);
        throw new AccessDeniedException("Not allowed.");
    }

    // ─── Internals ──────────────────────────────────────────────────────

    private record Content(String title, String message) {}

    private record StudentScope(long schoolId, long sessionId, long classId, Long sectionId) {}

    /**
     * One of the teacher's own current-session timetable entries for exactly this class and
     * section (null = sectionless class) and, when given, this subject.
     */
    private Optional<TimetableEntry> teachingEntry(Long schoolId, Long sessionId, String teacherId, Long classId,
                                                   Long sectionId, String subjectName) {
        return timetable.findByAcademicSessionIdAndTeacherIdAndSchoolId(sessionId, teacherId, schoolId).stream()
                .filter(e -> teacherId.equals(e.getTeacherId()))
                .filter(e -> classId.equals(e.getClassId()))
                .filter(e -> Objects.equals(sectionId, e.getSectionId()))
                .filter(e -> subjectName == null || subjectName.equalsIgnoreCase(blankToNull(e.getSubjectName())))
                .findFirst();
    }

    private Content validatedContent(String title, String message) {
        String t = title == null ? "" : title.trim();
        String m = message == null ? "" : message.trim();
        if (t.isEmpty()) throw new IllegalArgumentException("Enter a title.");
        if (m.isEmpty()) throw new IllegalArgumentException("Enter a message.");
        if (t.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("Title must be at most " + MAX_TITLE_LENGTH + " characters.");
        }
        if (m.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("Message must be at most " + MAX_MESSAGE_LENGTH + " characters.");
        }
        return new Content(t, m);
    }

    /**
     * Optional. A new expiry must be in the future and within a year. On edit, an unchanged
     * expiry is kept as-is even if it has already passed, so an expired update can still be fixed.
     */
    private Instant validatedExpiry(Instant requested, Instant current) {
        if (requested == null) return null;
        if (requested.equals(current)) return current;
        Instant now = clock.instant();
        if (!requested.isAfter(now)) throw new IllegalArgumentException("The expiry must be in the future.");
        if (requested.isAfter(now.plus(MAX_EXPIRY))) {
            throw new IllegalArgumentException("The expiry can be at most one year away.");
        }
        return requested;
    }

    /**
     * Sets or clears the single attachment. The attachment already on the update is kept as-is;
     * any other key is only a reference and must be this teacher's own COMPLETED
     * CLASS_UPDATE_ATTACHMENT upload in this school, not attached to any other update. Content
     * type and size come from that verified upload.
     */
    private void applyAttachment(ClassUpdate update, AttachmentRef ref, Long schoolId, String teacherId) {
        if (ref == null) {
            clearAttachment(update);
            return;
        }
        if (ref.objectKey() == null || ref.objectKey().isBlank()) throw new IllegalArgumentException("Invalid attachment reference.");
        String key = ref.objectKey().trim();
        if (key.equals(update.getAttachmentObjectKey())) return;
        UploadIntent intent = verifiedUpload(key, schoolId, teacherId);
        if (updates.existsByAttachmentObjectKey(key)) throw new IllegalArgumentException("Invalid attachment reference.");
        update.setAttachmentObjectKey(key);
        update.setAttachmentContentType(intent.getExpectedContentType());
        update.setAttachmentFileSize(intent.getExpectedSize());
        update.setAttachmentFileName(cleanFileName(ref.fileName(), key));
    }

    private static void clearAttachment(ClassUpdate update) {
        update.setAttachmentObjectKey(null);
        update.setAttachmentFileName(null);
        update.setAttachmentContentType(null);
        update.setAttachmentFileSize(null);
    }

    private UploadIntent verifiedUpload(String objectKey, Long schoolId, String teacherId) {
        return uploadIntents.findByObjectKey(objectKey)
                .filter(i -> schoolId.equals(i.getSchoolId()))
                .filter(i -> teacherId.equals(i.getRequestedByUserId()))
                .filter(i -> UploadPurpose.CLASS_UPDATE_ATTACHMENT.name().equals(i.getPurpose()))
                .filter(i -> UploadIntent.STATUS_COMPLETED.equals(i.getStatus()))
                .orElseThrow(() -> new IllegalArgumentException("Invalid attachment reference."));
    }

    /** Display-only: strips any path and control characters; falls back to the object key's name. */
    static String cleanFileName(String fileName, String objectKey) {
        String name = fileName == null ? "" : fileName.replaceAll("[\\p{Cntrl}]", "").trim();
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1).trim();
        if (name.isEmpty()) name = objectKey.substring(objectKey.lastIndexOf('/') + 1);
        return name.length() <= MAX_FILE_NAME_LENGTH ? name : name.substring(0, MAX_FILE_NAME_LENGTH);
    }

    private ClassUpdate save(ClassUpdate update) {
        try {
            return updates.saveAndFlush(update);
        } catch (DataIntegrityViolationException duplicateAttachment) {
            throw new IllegalArgumentException("Invalid attachment reference.");
        }
    }

    private ClassUpdate ownUpdate(long id) {
        Long schoolId = requireSchool();
        ClassUpdate update = updates.findByIdAndSchoolId(id, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Update not found."));
        if (!update.getTeacherId().equals(security.getUsername())) {
            throw new AccessDeniedException("You can only change your own updates.");
        }
        return update;
    }

    /** The student's own ACTIVE enrollment effective today in the current session, if any. */
    private Optional<StudentScope> studentScope() {
        Long schoolId = requireSchool();
        AcademicSession session = sessions.currentSessionOrNull(schoolId);
        if (session == null) return Optional.empty();
        return enrollments.findRealizedEffectiveEnrollments(schoolId, security.getUsername(), session.getId(),
                        LocalDate.now(clock)).stream()
                .filter(e -> e.getStatus() == StudentEnrollmentStatus.ACTIVE)
                .max(Comparator.comparing(StudentEnrollment::getEffectiveFrom).thenComparing(StudentEnrollment::getId))
                .map(e -> new StudentScope(schoolId, session.getId(), e.getClassId(), e.getSectionId()));
    }

    private Long requireSchool() {
        Long schoolId = security.getSchoolId();
        if (schoolId == null) throw new IllegalArgumentException("No school context for the current session.");
        return schoolId;
    }

    private boolean isExpired(ClassUpdate u) {
        return u.getExpiresAt() != null && !u.getExpiresAt().isAfter(clock.instant());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Numeric class names ("2" before "10") first, then the rest alphabetically. */
    private static int compareClassNames(String a, String b) {
        boolean an = a.matches("\\d+"), bn = b.matches("\\d+");
        if (an && bn) return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
        if (an != bn) return an ? -1 : 1;
        return a.compareToIgnoreCase(b);
    }

    private UpdateView toView(ClassUpdate u, boolean owner) {
        AttachmentView file = u.getAttachmentObjectKey() == null ? null
                : new AttachmentView(u.getAttachmentFileName(), u.getAttachmentContentType(),
                        u.getAttachmentFileSize() == null ? 0 : u.getAttachmentFileSize(),
                        "application/pdf".equals(u.getAttachmentContentType()) ? "PDF" : "IMAGE",
                        objectStorage.resolveDisplayUrl(u.getAttachmentObjectKey()),
                        owner ? u.getAttachmentObjectKey() : null);
        return new UpdateView(u.getId(), u.getClassId(), u.getClassName(), u.getSectionId(), u.getSectionName(),
                u.getSubjectName(), u.getTeacherId(), u.getTeacherName(), u.getTitle(), u.getMessage(), file,
                u.getExpiresAt(), isExpired(u), u.getCreatedAt(), u.getUpdatedAt(), owner);
    }
}
