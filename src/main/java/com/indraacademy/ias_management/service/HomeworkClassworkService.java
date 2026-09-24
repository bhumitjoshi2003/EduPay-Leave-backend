package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.HomeworkClassworkDtos.AttachmentRef;
import com.indraacademy.ias_management.dto.HomeworkClassworkDtos.AttachmentView;
import com.indraacademy.ias_management.dto.HomeworkClassworkDtos.CreateRequest;
import com.indraacademy.ias_management.dto.HomeworkClassworkDtos.UpdateRequest;
import com.indraacademy.ias_management.dto.HomeworkClassworkDtos.WorkView;
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
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Homework & Classwork (Phase 1). A teacher posts for a timetable period they teach (their own
 * entry, or one they cover by an ACTIVE substitution that day) in the school's current session;
 * students see posts for the class/section they are actively enrolled in. Only the posting
 * teacher can edit or delete. Notifies the class once, on create only.
 */
@Service
public class HomeworkClassworkService {
    static final int MAX_TEXT_LENGTH = 5000;
    static final int RECENT_LIMIT = 30;
    static final int UPCOMING_LIMIT = 50;
    static final int MAX_ATTACHMENTS = 5;
    static final int MAX_FILE_NAME_LENGTH = 255;

    private final HomeworkClassworkRepository works;
    private final HomeworkClassworkAttachmentRepository attachments;
    private final TimetableRepository timetable;
    private final TeacherSubstitutionRepository substitutions;
    private final TeacherRepository teachers;
    private final StudentEnrollmentRepository enrollments;
    private final TimetableSessionAccessService sessions;
    private final UploadIntentRepository uploadIntents;
    private final ObjectStorageService objectStorage;
    private final SecurityUtil security;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public HomeworkClassworkService(HomeworkClassworkRepository works, HomeworkClassworkAttachmentRepository attachments,
                                    TimetableRepository timetable,
                                    TeacherSubstitutionRepository substitutions, TeacherRepository teachers,
                                    StudentEnrollmentRepository enrollments, TimetableSessionAccessService sessions,
                                    UploadIntentRepository uploadIntents, ObjectStorageService objectStorage,
                                    SecurityUtil security, ApplicationEventPublisher events, Clock clock) {
        this.works = works;
        this.attachments = attachments;
        this.timetable = timetable;
        this.substitutions = substitutions;
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

    @Transactional
    public WorkView create(CreateRequest request) {
        Long schoolId = requireSchool();
        String teacherId = security.getUsername();
        if (request.timetableEntryId() == null) throw new IllegalArgumentException("A timetable period is required.");

        AcademicSession session = sessions.requireCurrentSessionForTeacherWrite(schoolId);
        TimetableEntry entry = timetable.findByIdAndSchoolId(request.timetableEntryId(), schoolId)
                .orElseThrow(() -> new NoSuchElementException("Timetable period not found."));
        if (!session.getId().equals(entry.getAcademicSessionId())) {
            throw new IllegalArgumentException("This period is not part of the current academic session.");
        }
        if (entry.getClassId() == null) {
            throw new IllegalArgumentException("This period is not linked to a class.");
        }

        LocalDate workDate = request.workDate() == null ? LocalDate.now(clock) : request.workDate();
        if (workDate.isBefore(session.getStartDate()) || workDate.isAfter(session.getEndDate())) {
            throw new IllegalArgumentException("The date is outside the current academic session.");
        }
        if (!entry.getDay().name().equals(workDate.getDayOfWeek().name())) {
            throw new IllegalArgumentException("This period is not scheduled on " + workDate + ".");
        }
        if (!teachesPeriod(schoolId, teacherId, entry, workDate)) {
            throw new AccessDeniedException("You can only post for periods you teach.");
        }

        Content content = validatedContent(request.classwork(), request.homework(), request.dueDate(), workDate);
        if (works.existsBySchoolIdAndTimetableEntryIdAndWorkDate(schoolId, entry.getId(), workDate)) {
            throw new IllegalStateException("Homework/classwork has already been posted for this period today. Edit it instead.");
        }
        HomeworkClasswork work = new HomeworkClasswork();
        work.setSchoolId(schoolId);
        work.setAcademicSessionId(session.getId());
        work.setTeacherId(teacherId);
        work.setTeacherName(teachers.findByTeacherIdAndSchoolId(teacherId, schoolId).map(Teacher::getName).orElse(null));
        work.setClassId(entry.getClassId());
        work.setClassName(entry.getClassName());
        work.setSectionId(entry.getSectionId());
        work.setSectionName(entry.getSectionName());
        work.setSubjectName(entry.getSubjectName());
        work.setTimetableEntryId(entry.getId());
        work.setWorkDate(workDate);
        work.setClasswork(content.classwork());
        work.setHomework(content.homework());
        work.setDueDate(content.dueDate());
        applyAttachments(work, request.attachments(), schoolId, teacherId);
        try {
            work = works.saveAndFlush(work);
        } catch (DataIntegrityViolationException duplicate) {
            throw new IllegalStateException("Homework/classwork has already been posted for this period today. Edit it instead.");
        }

        events.publishEvent(new HomeworkClassworkPostedEvent(work.getId(), schoolId, session.getId(), work.getClassId(),
                work.getSectionId(), work.getClassName(), work.getSectionName(), work.getSubjectName(),
                work.getClasswork() != null, work.getHomework() != null, workDate, work.getDueDate(), teacherId));
        return toView(work, true);
    }

    /** Content-only edit by the posting teacher. Deliberately does not notify students (Phase 1). */
    @Transactional
    public WorkView update(long id, UpdateRequest request) {
        HomeworkClasswork work = ownWork(id);
        Content content = validatedContent(request.classwork(), request.homework(), request.dueDate(), work.getWorkDate());
        work.setClasswork(content.classwork());
        work.setHomework(content.homework());
        work.setDueDate(content.dueDate());
        applyAttachments(work, request.attachments(), work.getSchoolId(), work.getTeacherId());
        return toView(works.saveAndFlush(work), true);
    }

    /** Deletion by the posting teacher. Does not notify students. */
    @Transactional
    public void delete(long id) {
        works.delete(ownWork(id));
    }

    @Transactional(readOnly = true)
    public List<WorkView> myPostsOn(LocalDate date) {
        Long schoolId = requireSchool();
        LocalDate day = date == null ? LocalDate.now(clock) : date;
        return works.findBySchoolIdAndTeacherIdAndWorkDateOrderByCreatedAtAsc(schoolId, security.getUsername(), day)
                .stream().map(w -> toView(w, true)).toList();
    }

    @Transactional(readOnly = true)
    public List<WorkView> myRecentPosts() {
        Long schoolId = requireSchool();
        return works.findBySchoolIdAndTeacherIdOrderByWorkDateDescIdDesc(schoolId, security.getUsername(),
                PageRequest.of(0, RECENT_LIMIT)).stream().map(w -> toView(w, true)).toList();
    }

    // ─── Student ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<WorkView> studentOn(LocalDate date) {
        LocalDate day = date == null ? LocalDate.now(clock) : date;
        return studentScope().map(s -> works.findForClassOnDate(s.schoolId, s.sessionId, s.classId, s.sectionId, day)
                .stream().map(w -> toView(w, false)).toList()).orElse(List.of());
    }

    @Transactional(readOnly = true)
    public List<WorkView> studentUpcoming() {
        LocalDate today = LocalDate.now(clock);
        return studentScope().map(s -> works.findHomeworkDueFrom(s.schoolId, s.sessionId, s.classId, s.sectionId, today,
                PageRequest.of(0, UPCOMING_LIMIT)).stream().map(w -> toView(w, false)).toList()).orElse(List.of());
    }

    @Transactional(readOnly = true)
    public List<WorkView> studentRecent() {
        LocalDate today = LocalDate.now(clock);
        return studentScope().map(s -> works.findForClassBefore(s.schoolId, s.sessionId, s.classId, s.sectionId, today,
                PageRequest.of(0, RECENT_LIMIT)).stream().map(w -> toView(w, false)).toList()).orElse(List.of());
    }

    // ─── Single post (teacher owner, student in that class, school ADMIN/SUB_ADMIN read-only) ──

    @Transactional(readOnly = true)
    public WorkView get(long id) {
        Long schoolId = requireSchool();
        HomeworkClasswork work = works.findByIdAndSchoolId(id, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Post not found."));
        String role = security.getRole();
        if (Role.TEACHER.equals(role)) {
            if (!work.getTeacherId().equals(security.getUsername())) throw new NoSuchElementException("Post not found.");
            return toView(work, true);
        }
        if (Role.STUDENT.equals(role)) {
            StudentScope scope = studentScope().orElseThrow(() -> new NoSuchElementException("Post not found."));
            boolean visible = work.getAcademicSessionId().equals(scope.sessionId) && work.getClassId().equals(scope.classId)
                    && (work.getSectionId() == null || work.getSectionId().equals(scope.sectionId));
            if (!visible) throw new NoSuchElementException("Post not found.");
            return toView(work, false);
        }
        if (Role.ADMIN.equals(role) || Role.SUB_ADMIN.equals(role)) return toView(work, false);
        throw new AccessDeniedException("Not allowed.");
    }

    // ─── Internals ──────────────────────────────────────────────────────

    private record Content(String classwork, String homework, LocalDate dueDate) {}

    private record StudentScope(long schoolId, long sessionId, long classId, Long sectionId) {}

    private boolean teachesPeriod(Long schoolId, String teacherId, TimetableEntry entry, LocalDate date) {
        if (teacherId.equals(entry.getTeacherId())) return true;
        return substitutions.findBySchoolIdAndDateAndTimetableEntryIdAndStatus(schoolId, date, entry.getId(),
                        TeacherSubstitutionStatus.ACTIVE)
                .map(s -> teacherId.equals(s.getSubstituteTeacherId()))
                .orElse(false);
    }

    private Content validatedContent(String classwork, String homework, LocalDate dueDate, LocalDate workDate) {
        String cw = clean(classwork, "Classwork");
        String hw = clean(homework, "Homework");
        if (cw == null && hw == null) throw new IllegalArgumentException("Enter classwork, homework, or both.");
        LocalDate due = hw == null ? null : dueDate;
        if (due != null && due.isBefore(workDate)) throw new IllegalArgumentException("The due date cannot be before the class date.");
        return new Content(cw, hw, due);
    }

    private String clean(String text, String label) {
        if (text == null || text.isBlank()) return null;
        String trimmed = text.trim();
        if (trimmed.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(label + " must be at most " + MAX_TEXT_LENGTH + " characters.");
        }
        return trimmed;
    }

    /**
     * Replaces the post's attachment list with {@code refs}, in order (max 5). An attachment
     * already on the post is kept as-is; every other key is only a reference and must be this
     * teacher's own COMPLETED HOMEWORK_ATTACHMENT upload in this school, not attached to any
     * other post. Content type and size come from that verified upload. Attachments left out are
     * removed (orphanRemoval).
     */
    private void applyAttachments(HomeworkClasswork work, List<AttachmentRef> refs, Long schoolId, String teacherId) {
        List<AttachmentRef> requested = refs == null ? List.of() : refs;
        if (requested.size() > MAX_ATTACHMENTS) {
            throw new IllegalArgumentException("A post can have at most " + MAX_ATTACHMENTS + " attachments.");
        }
        Map<String, HomeworkClassworkAttachment> existing = work.getAttachments().stream()
                .collect(Collectors.toMap(HomeworkClassworkAttachment::getObjectKey, Function.identity()));
        Set<String> seen = new HashSet<>();
        List<HomeworkClassworkAttachment> result = new ArrayList<>(requested.size());
        int order = 0;
        for (AttachmentRef ref : requested) {
            if (ref == null || ref.objectKey() == null || ref.objectKey().isBlank()) {
                throw new IllegalArgumentException("Invalid attachment reference.");
            }
            String key = ref.objectKey().trim();
            if (!seen.add(key)) throw new IllegalArgumentException("The same attachment was added twice.");
            HomeworkClassworkAttachment attachment = existing.get(key);
            if (attachment == null) {
                UploadIntent intent = verifiedUpload(key, schoolId, teacherId);
                if (attachments.existsByObjectKey(key)) throw new IllegalArgumentException("Invalid attachment reference.");
                attachment = new HomeworkClassworkAttachment();
                attachment.setHomeworkClasswork(work);
                attachment.setObjectKey(key);
                attachment.setContentType(intent.getExpectedContentType());
                attachment.setFileSize(intent.getExpectedSize());
                attachment.setFileName(cleanFileName(ref.fileName(), key));
            }
            attachment.setSortOrder(order++);
            result.add(attachment);
        }
        work.getAttachments().clear();
        work.getAttachments().addAll(result);
    }

    private UploadIntent verifiedUpload(String objectKey, Long schoolId, String teacherId) {
        return uploadIntents.findByObjectKey(objectKey)
                .filter(i -> schoolId.equals(i.getSchoolId()))
                .filter(i -> teacherId.equals(i.getRequestedByUserId()))
                .filter(i -> UploadPurpose.HOMEWORK_ATTACHMENT.name().equals(i.getPurpose()))
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

    private HomeworkClasswork ownWork(long id) {
        Long schoolId = requireSchool();
        HomeworkClasswork work = works.findByIdAndSchoolId(id, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Post not found."));
        if (!work.getTeacherId().equals(security.getUsername())) {
            throw new AccessDeniedException("You can only change your own posts.");
        }
        return work;
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

    private WorkView toView(HomeworkClasswork w, boolean owner) {
        List<AttachmentView> files = w.getAttachments().stream()
                .map(a -> new AttachmentView(a.getId() == null ? 0 : a.getId(), a.getFileName(), a.getContentType(),
                        a.getFileSize(), "application/pdf".equals(a.getContentType()) ? "PDF" : "IMAGE",
                        objectStorage.resolveDisplayUrl(a.getObjectKey()), owner ? a.getObjectKey() : null))
                .toList();
        return new WorkView(w.getId(), w.getWorkDate(), w.getClassId(), w.getClassName(), w.getSectionId(),
                w.getSectionName(), w.getSubjectName(), w.getTeacherId(), w.getTeacherName(), w.getTimetableEntryId(),
                w.getClasswork(), w.getHomework(), w.getDueDate(), files, w.getCreatedAt(), w.getUpdatedAt(), owner);
    }
}
