package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.AssessmentDtos.AssessmentView;
import com.indraacademy.ias_management.dto.AssessmentDtos.AttachmentRef;
import com.indraacademy.ias_management.dto.AssessmentDtos.AttachmentView;
import com.indraacademy.ias_management.dto.AssessmentDtos.ContextClass;
import com.indraacademy.ias_management.dto.AssessmentDtos.ContextSection;
import com.indraacademy.ias_management.dto.AssessmentDtos.CreateRequest;
import com.indraacademy.ias_management.dto.AssessmentDtos.UpdateRequest;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SchoolTimeUtil;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.*;

/**
 * Exam & Assessment Calendar (Phase 1). TEACHERs schedule assessments for class/section/subject
 * contexts they teach (their own current-session timetable); school ADMINs for any active class
 * of their school. SUB_ADMIN has no assessment permission and fails closed. School and session
 * are always resolved on the server. The creator or a school ADMIN may edit/delete. Teachers also
 * VIEW (read-only) assessments relevant to them: their subject in a class/section they teach, or
 * any assessment for the class/section they are class teacher of. Students see
 * their active enrollment's class/section (plus whole-class assessments) in the current session.
 * Students are notified on create, on a material edit (date/time/title), and once the day before.
 */
@Service
public class AssessmentService {
    static final int MAX_TITLE_LENGTH = 150;
    static final int MAX_SYLLABUS_LENGTH = 3000;
    static final int MAX_INSTRUCTIONS_LENGTH = 2000;
    static final int MAX_SUBJECT_LENGTH = 100;
    static final int MAX_FILE_NAME_LENGTH = 255;
    static final int MANAGE_UPCOMING_LIMIT = 100;
    static final int MANAGE_PAST_LIMIT = 50;
    static final int STUDENT_LIMIT = 50;
    /** Rows fetched before the teacher relevance filter is applied in memory. */
    static final int TEACHER_CANDIDATE_LIMIT = 500;

    private final AssessmentRepository assessments;
    private final TimetableRepository timetable;
    private final SchoolClassRepository classes;
    private final SectionRepository sections;
    private final ClassSubjectRepository classSubjects;
    private final TeacherRepository teachers;
    private final AdminRepository admins;
    private final SchoolRepository schools;
    private final StudentEnrollmentRepository enrollments;
    private final TimetableSessionAccessService sessions;
    private final TeacherClassScopeService classScope;
    private final UploadIntentRepository uploadIntents;
    private final ObjectStorageService objectStorage;
    private final SecurityUtil security;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public AssessmentService(AssessmentRepository assessments, TimetableRepository timetable, SchoolClassRepository classes,
                             SectionRepository sections, ClassSubjectRepository classSubjects, TeacherRepository teachers,
                             AdminRepository admins, SchoolRepository schools, StudentEnrollmentRepository enrollments,
                             TimetableSessionAccessService sessions, TeacherClassScopeService classScope,
                             UploadIntentRepository uploadIntents,
                             ObjectStorageService objectStorage, SecurityUtil security, ApplicationEventPublisher events,
                             Clock clock) {
        this.assessments = assessments;
        this.timetable = timetable;
        this.classes = classes;
        this.sections = sections;
        this.classSubjects = classSubjects;
        this.teachers = teachers;
        this.admins = admins;
        this.schools = schools;
        this.enrollments = enrollments;
        this.sessions = sessions;
        this.classScope = classScope;
        this.uploadIntents = uploadIntents;
        this.objectStorage = objectStorage;
        this.security = security;
        this.events = events;
        this.clock = clock;
    }

    // ─── Contexts ───────────────────────────────────────────────────────

    /** What the caller may target: TEACHER — their own timetable; ADMIN — every active class. */
    @Transactional(readOnly = true)
    public List<ContextClass> contexts() {
        Actor actor = requireManager();
        AcademicSession session = sessions.currentSessionOrNull(actor.schoolId);
        if (session == null) return List.of();
        return actor.isAdmin() ? adminContexts(actor.schoolId) : teacherContexts(actor, session.getId());
    }

    // ─── Create / edit / delete ─────────────────────────────────────────

    @Transactional
    public AssessmentView create(CreateRequest request) {
        Actor actor = requireManager();
        AcademicSession session = sessions.requireCurrentSessionForTeacherWrite(actor.schoolId);
        if (request.classId() == null) throw new IllegalArgumentException("Choose a class.");
        Target target = actor.isAdmin()
                ? adminTarget(actor.schoolId, request.classId(), request.sectionId(), request.subjectName())
                : teacherTarget(actor, session.getId(), request.classId(), request.sectionId(), request.subjectName());

        LocalDate today = schoolToday(actor.schoolId);
        Content content = validatedContent(request.assessmentType(), request.title(), request.syllabus(),
                request.instructions(), request.startTime(), request.endTime());
        LocalDate date = validatedDate(request.assessmentDate(), null, session, today);

        Assessment a = new Assessment();
        a.setSchoolId(actor.schoolId);
        a.setAcademicSessionId(session.getId());
        a.setCreatedByUserId(actor.userId);
        a.setCreatedByRole(actor.role);
        a.setCreatedByName(displayName(actor));
        a.setClassId(target.classId);
        a.setClassName(target.className);
        a.setSectionId(target.sectionId);
        a.setSectionName(target.sectionName);
        a.setSubjectName(target.subjectName);
        apply(a, content, date);
        // Scheduled for today or tomorrow: the "scheduled" notification already serves as the reminder.
        a.setReminderSentAt(date.isAfter(today.plusDays(1)) ? null : clock.instant());
        applyAttachment(a, request.attachment(), actor);
        a = save(a);

        events.publishEvent(event(AssessmentNotificationEvent.Kind.SCHEDULED, a, actor.userId));
        return toView(a, Access.full(true));
    }

    /**
     * Edit by the creator or a school ADMIN; class/section/subject are fixed. Students are
     * notified only when the date, time or title changes. Rescheduling re-arms the reminder.
     */
    @Transactional
    public AssessmentView update(long id, UpdateRequest request) {
        Actor actor = requireManager();
        Assessment a = editable(actor, id);
        AcademicSession session = sessions.requireCurrentSessionForTeacherWrite(actor.schoolId);
        if (!session.getId().equals(a.getAcademicSessionId())) {
            throw new IllegalStateException("Assessments from a past session are read-only.");
        }
        LocalDate today = schoolToday(actor.schoolId);
        Content content = validatedContent(request.assessmentType(), request.title(), request.syllabus(),
                request.instructions(), request.startTime(), request.endTime());
        LocalDate date = validatedDate(request.assessmentDate(), a.getAssessmentDate(), session, today);

        boolean material = !date.equals(a.getAssessmentDate()) || !Objects.equals(content.start, a.getStartTime())
                || !Objects.equals(content.end, a.getEndTime()) || !content.title.equals(a.getTitle());
        if (!date.equals(a.getAssessmentDate())) {
            a.setReminderSentAt(date.isAfter(today.plusDays(1)) ? null : clock.instant());
        }
        apply(a, content, date);
        applyAttachment(a, request.attachment(), actor);
        a = save(a);

        if (material) events.publishEvent(event(AssessmentNotificationEvent.Kind.UPDATED, a, actor.userId));
        return toView(a, access(actor, a, null));
    }

    /** Deletion by the creator or a school ADMIN. No notification; a pending reminder dies with the row. */
    @Transactional
    public void delete(long id) {
        Actor actor = requireManager();
        assessments.delete(editable(actor, id));
    }

    // ─── Manage lists ───────────────────────────────────────────────────

    /**
     * scope "upcoming" (today onwards, soonest first) or "past" (newest first). ADMIN: the whole
     * school. TEACHER: their own plus relevant ones (subject teacher / class teacher), each once,
     * with the effective canEdit/canDelete computed here — never inferred by the client.
     */
    @Transactional(readOnly = true)
    public List<AssessmentView> manage(String scope) {
        Actor actor = requireManager();
        AcademicSession session = sessions.currentSessionOrNull(actor.schoolId);
        if (session == null) return List.of();
        LocalDate today = schoolToday(actor.schoolId);
        boolean past = "past".equalsIgnoreCase(scope);
        int limit = past ? MANAGE_PAST_LIMIT : MANAGE_UPCOMING_LIMIT;
        if (actor.isAdmin()) {
            List<Assessment> rows = past
                    ? assessments.findBySchoolIdAndAcademicSessionIdAndAssessmentDateBeforeOrderByAssessmentDateDescIdDesc(
                            actor.schoolId, session.getId(), today, PageRequest.of(0, limit))
                    : assessments.findBySchoolIdAndAcademicSessionIdAndAssessmentDateGreaterThanEqualOrderByAssessmentDateAscStartTimeAscIdAsc(
                            actor.schoolId, session.getId(), today, PageRequest.of(0, limit));
            return rows.stream().map(r -> toView(r, access(actor, r, null))).toList();
        }
        TeacherRelations relations = teacherRelations(actor, session.getId());
        List<Long> classIds = relations.classIds().isEmpty() ? List.of(-1L) : List.copyOf(relations.classIds());
        PageRequest candidates = PageRequest.of(0, TEACHER_CANDIDATE_LIMIT);
        List<Assessment> rows = past
                ? assessments.findTeacherCandidatesBefore(actor.schoolId, session.getId(), actor.userId, classIds, today, candidates)
                : assessments.findTeacherCandidatesFrom(actor.schoolId, session.getId(), actor.userId, classIds, today, candidates);
        return rows.stream()
                .map(r -> Map.entry(r, access(actor, r, relations)))
                .filter(e -> e.getValue().canView())
                .limit(limit)
                .map(e -> toView(e.getKey(), e.getValue()))
                .toList();
    }

    // ─── Student ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AssessmentView> studentUpcoming(Integer limit) {
        int size = limit == null ? STUDENT_LIMIT : Math.max(1, Math.min(limit, STUDENT_LIMIT));
        return studentScope().map(s -> assessments.findForClassFrom(s.schoolId, s.sessionId, s.classId, s.sectionId,
                s.today, PageRequest.of(0, size)).stream().map(a -> toView(a, Access.STUDENT)).toList()).orElse(List.of());
    }

    /** Every assessment in the given month (default: the school's current month), soonest first. */
    @Transactional(readOnly = true)
    public List<AssessmentView> studentMonth(YearMonth month) {
        return studentScope().map(s -> {
            YearMonth m = month == null ? YearMonth.from(s.today) : month;
            return assessments.findForClassBetween(s.schoolId, s.sessionId, s.classId, s.sectionId,
                    m.atDay(1), m.atEndOfMonth()).stream().map(a -> toView(a, Access.STUDENT)).toList();
        }).orElse(List.of());
    }

    @Transactional(readOnly = true)
    public List<AssessmentView> studentPast() {
        return studentScope().map(s -> assessments.findForClassBefore(s.schoolId, s.sessionId, s.classId, s.sectionId,
                s.today, PageRequest.of(0, STUDENT_LIMIT)).stream().map(a -> toView(a, Access.STUDENT)).toList()).orElse(List.of());
    }

    // ─── Single (school ADMIN, creator / relevant teacher, student of that class this session) ──

    @Transactional(readOnly = true)
    public AssessmentView get(long id) {
        Long schoolId = requireSchool();
        Assessment a = assessments.findByIdAndSchoolId(id, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Assessment not found."));
        String role = security.getRole();
        if (Role.ADMIN.equals(role) || Role.TEACHER.equals(role)) {
            Actor actor = new Actor(schoolId, security.getUsername(), role);
            Access access = access(actor, a, null);
            if (!access.canView()) throw new NoSuchElementException("Assessment not found.");
            return toView(a, access);
        }
        if (Role.STUDENT.equals(role)) {
            StudentScope s = studentScope().orElseThrow(() -> new NoSuchElementException("Assessment not found."));
            boolean visible = a.getAcademicSessionId().equals(s.sessionId) && a.getClassId().equals(s.classId)
                    && (a.getSectionId() == null || a.getSectionId().equals(s.sectionId));
            if (!visible) throw new NoSuchElementException("Assessment not found.");
            return toView(a, Access.STUDENT);
        }
        throw new AccessDeniedException("Not allowed.");
    }

    // ─── Internals ──────────────────────────────────────────────────────

    private record Actor(long schoolId, String userId, String role) {
        boolean isAdmin() { return Role.ADMIN.equals(role); }
    }

    private record Target(long classId, String className, Long sectionId, String sectionName, String subjectName) {}

    private record Content(AssessmentType type, String title, String syllabus, String instructions,
                           LocalTime start, LocalTime end) {}

    private record StudentScope(long schoolId, long sessionId, long classId, Long sectionId, LocalDate today) {}

    /** Only TEACHER and school ADMIN manage assessments; everyone else (incl. SUB_ADMIN) fails closed. */
    private Actor requireManager() {
        Long schoolId = requireSchool();
        String role = security.getRole();
        if (!Role.TEACHER.equals(role) && !Role.ADMIN.equals(role)) {
            throw new AccessDeniedException("Only teachers and school admins can manage assessments.");
        }
        return new Actor(schoolId, security.getUsername(), role);
    }

    private Long requireSchool() {
        Long schoolId = security.getSchoolId();
        if (schoolId == null) throw new IllegalArgumentException("No school context for the current session.");
        return schoolId;
    }

    /** What the caller may do with one assessment; always computed on the server. */
    record Access(boolean canView, boolean canEdit, boolean canDelete, boolean createdByCurrentUser) {
        static final Access NONE = new Access(false, false, false, false);
        static final Access STUDENT = new Access(true, false, false, false);
        static Access full(boolean createdByCurrentUser) { return new Access(true, true, true, createdByCurrentUser); }
        static Access readOnly() { return new Access(true, false, false, false); }
    }

    /**
     * The teacher's relationships in the current session, resolved once per request from their own
     * timetable entries and their own class-teacher assignment — never from client input.
     */
    private record TeacherRelations(List<TimetableEntry> entries, Long classTeacherClassId,
                                    Long classTeacherSectionId, long sessionId) {
        Set<Long> classIds() {
            Set<Long> ids = new HashSet<>();
            entries.forEach(e -> ids.add(e.getClassId()));
            if (classTeacherClassId != null) ids.add(classTeacherClassId);
            return ids;
        }
    }

    /**
     * ADMIN (same school): full access. Creator: full access. Otherwise a TEACHER may view an
     * assessment of the current session that is relevant to them, read-only unless a delegated
     * edit permission is granted (see {@link #hasDelegatedEditPermission}). Anyone else: none.
     * {@code relations} may be null; it is then resolved only if actually needed.
     */
    private Access access(Actor actor, Assessment a, TeacherRelations relations) {
        boolean mine = a.getCreatedByUserId().equals(actor.userId);
        if (actor.isAdmin() || mine) return Access.full(mine);
        if (!Role.TEACHER.equals(actor.role)) return Access.NONE;
        TeacherRelations r = relations != null ? relations : teacherRelations(actor, a.getAcademicSessionId());
        if (!a.getAcademicSessionId().equals(r.sessionId()) || !(isSubjectTeacher(r, a) || isClassTeacher(r, a))) {
            return Access.NONE;
        }
        boolean delegated = hasDelegatedEditPermission(actor);
        return new Access(true, delegated, delegated, false);
    }

    /**
     * Hook for letting a relevant (non-creator) teacher edit/delete. No backend-enforced
     * assessment-management permission exists today — the role permission matrix has no
     * assessment key, and its keys are role-wide (they would grant every teacher at once) — so
     * relevant teachers stay read-only. Replace this with a real, enforced check to enable it.
     */
    private boolean hasDelegatedEditPermission(Actor actor) {
        return false;
    }

    /** Only for the current session: a teacher's relations are about who they teach now. */
    private TeacherRelations teacherRelations(Actor actor, long sessionId) {
        AcademicSession current = sessions.currentSessionOrNull(actor.schoolId);
        if (current == null || current.getId() != sessionId) return new TeacherRelations(List.of(), null, null, -1L);
        List<TimetableEntry> entries = timetable.findByAcademicSessionIdAndTeacherIdAndSchoolId(sessionId, actor.userId, actor.schoolId)
                .stream()
                .filter(e -> actor.userId.equals(e.getTeacherId()) && e.getClassId() != null && blankToNull(e.getSubjectName()) != null)
                .toList();
        Long ctClassId = null;
        Long ctSectionId = null;
        TeacherClassScopeService.TeacherScope scope = classScope.resolveOwnScope(actor.userId, actor.schoolId);
        // A class that has sections but no assigned section is ambiguous — fail closed, as elsewhere.
        if (scope.hasClassResponsibility() && !scope.sectionRequiredButMissing()) {
            ctClassId = classes.findBySchoolIdAndName(actor.schoolId, scope.className()).map(SchoolClass::getId).orElse(null);
            ctSectionId = scope.sectionId();
        }
        return new TeacherRelations(entries, ctClassId, ctSectionId, sessionId);
    }

    /**
     * Teaches the assessment's subject in its class — in its section, or in any section of the
     * class for a whole-class assessment (it covers their students too). A different subject never
     * qualifies, so whole-class assessments are not shown to every subject teacher of the class.
     */
    private static boolean isSubjectTeacher(TeacherRelations r, Assessment a) {
        return r.entries().stream().anyMatch(e -> a.getClassId().equals(e.getClassId())
                && a.getSubjectName().equalsIgnoreCase(e.getSubjectName().trim())
                && (a.getSectionId() == null || e.getSectionId() == null || a.getSectionId().equals(e.getSectionId())));
    }

    /** Class teacher of the assessment's class: their own section's and whole-class assessments (every one for a sectionless class). */
    private static boolean isClassTeacher(TeacherRelations r, Assessment a) {
        return r.classTeacherClassId() != null && r.classTeacherClassId().equals(a.getClassId())
                && (r.classTeacherSectionId() == null || a.getSectionId() == null || r.classTeacherSectionId().equals(a.getSectionId()));
    }

    private Assessment editable(Actor actor, long id) {
        Assessment a = assessments.findByIdAndSchoolId(id, actor.schoolId)
                .orElseThrow(() -> new NoSuchElementException("Assessment not found."));
        Access access = access(actor, a, null);
        if (!access.canView()) throw new NoSuchElementException("Assessment not found.");
        if (!access.canEdit()) throw new AccessDeniedException("You can only change assessments you created.");
        return a;
    }

    private LocalDate schoolToday(long schoolId) {
        School school = schools.findById(schoolId).orElse(null);
        return LocalDate.now(clock.withZone(SchoolTimeUtil.zoneId(school)));
    }

    private String displayName(Actor actor) {
        return actor.isAdmin()
                ? admins.findByAdminIdAndSchoolId(actor.userId, actor.schoolId).map(Admin::getName).orElse(null)
                : teachers.findByTeacherIdAndSchoolId(actor.userId, actor.schoolId).map(Teacher::getName).orElse(null);
    }

    // Targets

    private Target teacherTarget(Actor actor, long sessionId, Long classId, Long sectionId, String subjectName) {
        String subject = blankToNull(subjectName);
        if (subject == null) throw new IllegalArgumentException("Choose a subject.");
        return timetable.findByAcademicSessionIdAndTeacherIdAndSchoolId(sessionId, actor.userId, actor.schoolId).stream()
                .filter(e -> actor.userId.equals(e.getTeacherId()))
                .filter(e -> classId.equals(e.getClassId()))
                .filter(e -> Objects.equals(sectionId, e.getSectionId()))
                .filter(e -> subject.equalsIgnoreCase(blankToNull(e.getSubjectName())))
                .findFirst()
                .map(e -> new Target(e.getClassId(), e.getClassName(), e.getSectionId(), e.getSectionName(), e.getSubjectName()))
                .orElseThrow(() -> new AccessDeniedException("You can only schedule assessments for classes and subjects you teach."));
    }

    private Target adminTarget(long schoolId, Long classId, Long sectionId, String subjectName) {
        SchoolClass cls = classes.findByIdAndSchoolId(classId, schoolId)
                .filter(SchoolClass::isActive)
                .orElseThrow(() -> new NoSuchElementException("Class not found."));
        Section section = null;
        if (sectionId != null) {
            section = sections.findByIdAndSchoolId(sectionId, schoolId)
                    .filter(s -> classId.equals(s.getClassId()) && s.isActive())
                    .orElseThrow(() -> new NoSuchElementException("Section not found."));
        }
        String subject = blankToNull(subjectName);
        if (subject == null) throw new IllegalArgumentException("Choose a subject.");
        List<String> configured = subjectsByClass(schoolId).getOrDefault(cls.getId(), List.of());
        if (!configured.isEmpty()) {
            subject = configured.stream().filter(subject::equalsIgnoreCase).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("That subject is not configured for this class."));
        } else if (subject.length() > MAX_SUBJECT_LENGTH) {
            throw new IllegalArgumentException("Subject must be at most " + MAX_SUBJECT_LENGTH + " characters.");
        }
        return new Target(cls.getId(), cls.getName(), section == null ? null : section.getId(),
                section == null ? null : section.getName(), subject);
    }

    // Contexts

    private List<ContextClass> teacherContexts(Actor actor, long sessionId) {
        record Key(long classId, Long sectionId) {}
        Map<Long, String> classNames = new HashMap<>();
        Map<Key, String> sectionNames = new LinkedHashMap<>();
        Map<Key, TreeSet<String>> subjects = new HashMap<>();
        for (TimetableEntry e : timetable.findByAcademicSessionIdAndTeacherIdAndSchoolId(sessionId, actor.userId, actor.schoolId)) {
            String subject = blankToNull(e.getSubjectName());
            if (e.getClassId() == null || subject == null) continue;
            Key key = new Key(e.getClassId(), e.getSectionId());
            classNames.putIfAbsent(e.getClassId(), e.getClassName());
            sectionNames.putIfAbsent(key, e.getSectionName());
            subjects.computeIfAbsent(key, k -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER)).add(subject);
        }
        Map<Long, List<ContextSection>> byClass = new HashMap<>();
        sectionNames.forEach((key, name) -> byClass.computeIfAbsent(key.classId, k -> new ArrayList<>())
                .add(new ContextSection(key.sectionId, name, List.copyOf(subjects.get(key)))));
        return classNames.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.nullsLast(AssessmentService::compareClassNames)))
                .map(c -> new ContextClass(c.getKey(), c.getValue(), byClass.get(c.getKey()).stream()
                        .sorted(Comparator.comparing(ContextSection::sectionName, Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER)))
                        .toList()))
                .toList();
    }

    private List<ContextClass> adminContexts(long schoolId) {
        Map<Long, List<String>> subjects = subjectsByClass(schoolId);
        Map<Long, List<Section>> sectionsByClass = new HashMap<>();
        for (Section s : sections.findBySchoolIdAndActiveOrderByDisplayOrderAsc(schoolId, true)) {
            sectionsByClass.computeIfAbsent(s.getClassId(), k -> new ArrayList<>()).add(s);
        }
        List<ContextClass> result = new ArrayList<>();
        for (SchoolClass cls : classes.findBySchoolIdAndActiveOrderByDisplayOrderAsc(schoolId, true)) {
            List<String> classSubjectNames = subjects.getOrDefault(cls.getId(), List.of());
            List<ContextSection> options = new ArrayList<>();
            options.add(new ContextSection(null, null, classSubjectNames));
            for (Section s : sectionsByClass.getOrDefault(cls.getId(), List.of())) {
                options.add(new ContextSection(s.getId(), s.getName(), classSubjectNames));
            }
            result.add(new ContextClass(cls.getId(), cls.getName(), options));
        }
        return result;
    }

    /** Configured subjects per class id (legacy rows without class_id are matched by class name). */
    private Map<Long, List<String>> subjectsByClass(long schoolId) {
        Map<String, Long> idByName = new HashMap<>();
        for (SchoolClass cls : classes.findBySchoolIdOrderByDisplayOrderAsc(schoolId)) idByName.put(cls.getName(), cls.getId());
        Map<Long, TreeSet<String>> byClass = new HashMap<>();
        for (ClassSubject cs : classSubjects.findBySchoolId(schoolId)) {
            Long classId = cs.getClassId() != null ? cs.getClassId() : idByName.get(cs.getClassName());
            String subject = blankToNull(cs.getSubjectName());
            if (classId == null || subject == null) continue;
            byClass.computeIfAbsent(classId, k -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER)).add(subject);
        }
        Map<Long, List<String>> result = new HashMap<>();
        byClass.forEach((k, v) -> result.put(k, List.copyOf(v)));
        return result;
    }

    // Validation

    private Content validatedContent(AssessmentType type, String title, String syllabus, String instructions,
                                     LocalTime start, LocalTime end) {
        if (type == null) throw new IllegalArgumentException("Choose an assessment type.");
        String t = required(title, "a title", "Title", MAX_TITLE_LENGTH);
        String s = required(syllabus, "the syllabus / portion", "Syllabus", MAX_SYLLABUS_LENGTH);
        String i = blankToNull(instructions);
        if (i != null && i.length() > MAX_INSTRUCTIONS_LENGTH) {
            throw new IllegalArgumentException("Instructions must be at most " + MAX_INSTRUCTIONS_LENGTH + " characters.");
        }
        if (end != null && start == null) throw new IllegalArgumentException("Add a start time before an end time.");
        if (start != null && end != null && !end.isAfter(start)) {
            throw new IllegalArgumentException("The end time must be after the start time.");
        }
        return new Content(type, t, s, i, start, end);
    }

    private static String required(String value, String what, String label, int max) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) throw new IllegalArgumentException("Enter " + what + ".");
        if (v.length() > max) throw new IllegalArgumentException(label + " must be at most " + max + " characters.");
        return v;
    }

    /**
     * Inside the current session; not in the past for a new or changed date. An unchanged date is
     * kept as-is on edit, so a past assessment's details can still be corrected.
     */
    private LocalDate validatedDate(LocalDate requested, LocalDate current, AcademicSession session, LocalDate today) {
        if (requested == null) throw new IllegalArgumentException("Choose the assessment date.");
        if (requested.equals(current)) return current;
        if (requested.isBefore(session.getStartDate()) || requested.isAfter(session.getEndDate())) {
            throw new IllegalArgumentException("The date must be inside the current academic session.");
        }
        if (requested.isBefore(today)) throw new IllegalArgumentException("The date cannot be in the past.");
        return requested;
    }

    private static void apply(Assessment a, Content c, LocalDate date) {
        a.setAssessmentType(c.type);
        a.setTitle(c.title);
        a.setSyllabus(c.syllabus);
        a.setInstructions(c.instructions);
        a.setAssessmentDate(date);
        a.setStartTime(c.start);
        a.setEndTime(c.end);
    }

    // Attachment

    /**
     * Sets or clears the single attachment. The one already on the assessment is kept as-is; any
     * other key must be the caller's own COMPLETED ASSESSMENT_ATTACHMENT upload in this school that
     * is not attached elsewhere. Content type and size come from that verified upload.
     */
    private void applyAttachment(Assessment a, AttachmentRef ref, Actor actor) {
        if (ref == null) {
            a.setAttachmentObjectKey(null);
            a.setAttachmentFileName(null);
            a.setAttachmentContentType(null);
            a.setAttachmentFileSize(null);
            return;
        }
        if (ref.objectKey() == null || ref.objectKey().isBlank()) throw new IllegalArgumentException("Invalid attachment reference.");
        String key = ref.objectKey().trim();
        if (key.equals(a.getAttachmentObjectKey())) return;
        UploadIntent intent = uploadIntents.findByObjectKey(key)
                .filter(i -> Objects.equals(actor.schoolId, i.getSchoolId()))
                .filter(i -> actor.userId.equals(i.getRequestedByUserId()))
                .filter(i -> UploadPurpose.ASSESSMENT_ATTACHMENT.name().equals(i.getPurpose()))
                .filter(i -> UploadIntent.STATUS_COMPLETED.equals(i.getStatus()))
                .orElseThrow(() -> new IllegalArgumentException("Invalid attachment reference."));
        if (assessments.existsByAttachmentObjectKey(key)) throw new IllegalArgumentException("Invalid attachment reference.");
        a.setAttachmentObjectKey(key);
        a.setAttachmentContentType(intent.getExpectedContentType());
        a.setAttachmentFileSize(intent.getExpectedSize());
        a.setAttachmentFileName(cleanFileName(ref.fileName(), key));
    }

    /** Display-only: strips any path and control characters; falls back to the object key's name. */
    static String cleanFileName(String fileName, String objectKey) {
        String name = fileName == null ? "" : fileName.replaceAll("[\\p{Cntrl}]", "").trim();
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1).trim();
        if (name.isEmpty()) name = objectKey.substring(objectKey.lastIndexOf('/') + 1);
        return name.length() <= MAX_FILE_NAME_LENGTH ? name : name.substring(0, MAX_FILE_NAME_LENGTH);
    }

    private Assessment save(Assessment a) {
        try {
            return assessments.saveAndFlush(a);
        } catch (DataIntegrityViolationException duplicateAttachment) {
            throw new IllegalArgumentException("Invalid attachment reference.");
        }
    }

    // Student scope

    /** The student's own ACTIVE enrollment effective today (school-local) in the current session. */
    private Optional<StudentScope> studentScope() {
        Long schoolId = requireSchool();
        AcademicSession session = sessions.currentSessionOrNull(schoolId);
        if (session == null) return Optional.empty();
        LocalDate today = schoolToday(schoolId);
        return enrollments.findRealizedEffectiveEnrollments(schoolId, security.getUsername(), session.getId(), today).stream()
                .filter(e -> e.getStatus() == StudentEnrollmentStatus.ACTIVE)
                .max(Comparator.comparing(StudentEnrollment::getEffectiveFrom).thenComparing(StudentEnrollment::getId))
                .map(e -> new StudentScope(schoolId, session.getId(), e.getClassId(), e.getSectionId(), today));
    }

    // Views

    private static AssessmentNotificationEvent event(AssessmentNotificationEvent.Kind kind, Assessment a, String actor) {
        return new AssessmentNotificationEvent(kind, a.getId(), a.getRevision(), a.getSchoolId(), a.getAcademicSessionId(),
                a.getClassId(), a.getSectionId(), a.getSubjectName(), a.getAssessmentType(), a.getTitle(),
                a.getAssessmentDate(), a.getStartTime(), actor);
    }

    private AssessmentView toView(Assessment a, Access access) {
        AttachmentView file = a.getAttachmentObjectKey() == null ? null
                : new AttachmentView(a.getAttachmentFileName(), a.getAttachmentContentType(),
                        a.getAttachmentFileSize() == null ? 0 : a.getAttachmentFileSize(),
                        "application/pdf".equals(a.getAttachmentContentType()) ? "PDF" : "IMAGE",
                        objectStorage.resolveDisplayUrl(a.getAttachmentObjectKey()),
                        access.canEdit() ? a.getAttachmentObjectKey() : null);
        return new AssessmentView(a.getId(), a.getAssessmentType(), a.getAssessmentType().label(), a.getTitle(),
                a.getClassId(), a.getClassName(), a.getSectionId(), a.getSectionName(), a.getSubjectName(),
                a.getAssessmentDate(), a.getStartTime(), a.getEndTime(), a.getSyllabus(), a.getInstructions(), file,
                a.getCreatedByUserId(), a.getCreatedByName(), a.getCreatedByRole(), a.getCreatedAt(), a.getUpdatedAt(),
                access.canEdit(), access.canDelete(), access.createdByCurrentUser());
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
}
