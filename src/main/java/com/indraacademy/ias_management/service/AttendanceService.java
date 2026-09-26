package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.AttendanceSheetDtos.SheetStudent;
import com.indraacademy.ias_management.dto.AttendanceSheetDtos.SheetView;
import com.indraacademy.ias_management.dto.AttendanceSheetDtos.StudentStatus;
import com.indraacademy.ias_management.dto.AttendanceSheetDtos.SubmitRequest;
import com.indraacademy.ias_management.dto.AttendanceSummaryDTO;
import com.indraacademy.ias_management.dto.ClassAttendanceSummaryDTO;
import com.indraacademy.ias_management.dto.ConsecutiveAbsenceDTO;
import com.indraacademy.ias_management.dto.DailyAttendanceDTO;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.service.AttendanceMath.Counts;
import com.indraacademy.ias_management.util.SchoolTimeUtil;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.TextStyle;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Student Attendance V2. A submission ({@link AttendanceSession}) is one class — or one section of
 * it — on one date, and holds an explicit PRESENT/ABSENT {@link StudentAttendance} row for every
 * eligible student. The roster, school, current session and authorization are always decided
 * here, never by the client: TEACHERs mark only their own class-teacher class/section, school
 * ADMINs any active class/section of their school. A date must be in the current session, a
 * configured working weekday, not a holiday, and not in the future — all judged in the school's
 * own timezone.
 *
 * <p>Every figure (student, class, school) is computed from explicit rows with the single
 * {@link AttendanceMath} formula: present / submitted days. APPROVED leave on an ABSENT day is
 * reported as approvedLeave but still counts as absent; pending/rejected leave is ignored.
 */
@Service
public class AttendanceService {
    private static final Logger log = LoggerFactory.getLogger(AttendanceService.class);
    public static final int DEFAULT_ABSENCE_LOOKBACK_DAYS = 60;

    private final AttendanceSessionRepository submissions;
    private final StudentAttendanceRepository rows;
    private final StudentEnrollmentRepository enrollments;
    private final StudentRepository students;
    private final SchoolRepository schools;
    private final SchoolClassRepository classes;
    private final SectionRepository sections;
    private final SchoolHolidayRepository holidays;
    private final LeaveRepository leaves;
    private final TimetableSessionAccessService sessionAccess;
    private final AcademicSessionService academicSessions;
    private final TeacherClassScopeService classScope;
    private final SecurityUtil security;
    private final AuditService audit;
    private final Clock clock;

    public AttendanceService(AttendanceSessionRepository submissions, StudentAttendanceRepository rows,
                             StudentEnrollmentRepository enrollments, StudentRepository students,
                             SchoolRepository schools, SchoolClassRepository classes, SectionRepository sections,
                             SchoolHolidayRepository holidays, LeaveRepository leaves,
                             TimetableSessionAccessService sessionAccess, AcademicSessionService academicSessions,
                             TeacherClassScopeService classScope, SecurityUtil security, AuditService audit,
                             Clock clock) {
        this.submissions = submissions;
        this.rows = rows;
        this.enrollments = enrollments;
        this.students = students;
        this.schools = schools;
        this.classes = classes;
        this.sections = sections;
        this.holidays = holidays;
        this.leaves = leaves;
        this.sessionAccess = sessionAccess;
        this.academicSessions = academicSessions;
        this.classScope = classScope;
        this.security = security;
        this.audit = audit;
        this.clock = clock;
    }

    // ─── Marking ─────────────────────────────────────────────────────────

    /**
     * The roster for one class (section) day with any saved statuses and APPROVED-leave hints.
     * Never fails on the date itself — markable/blockedReason say whether it can be submitted.
     */
    @Transactional(readOnly = true)
    public SheetView getSheet(LocalDate date, Long classId, Long sectionId) {
        Actor actor = requireMarker();
        School school = requireSchoolEntity(actor.schoolId);
        AcademicSession session = sessionAccess.currentSessionOrNull(actor.schoolId);
        Scope scope = resolveScope(actor, classId, sectionId);
        LocalDate day = date != null ? date : schoolToday(school);
        String blocked = blockedReason(school, session, day);

        Optional<AttendanceSession> submission = session == null ? Optional.empty()
                : submissions.findSubmission(actor.schoolId, session.getId(), scope.classId, scope.sectionId, day);
        Map<String, AttendanceStatus> saved = submission
                .map(s -> rows.findByAttendanceSessionId(s.getId()).stream()
                        .collect(Collectors.toMap(StudentAttendance::getStudentId, StudentAttendance::getStatus, (a, b) -> a)))
                .orElse(Map.of());
        List<String> rosterIds = session == null ? List.of()
                : enrollments.findAttendanceRosterStudentIds(actor.schoolId, session.getId(), scope.classId, scope.sectionId, day);
        return sheet(actor.schoolId, scope, day, submission.orElse(null), rosterIds, saved, blocked);
    }

    /**
     * Saves a full submission. Re-submitting the same class (section) day updates it in place;
     * a concurrent or repeated submit (double-click, retry) serializes on the submission row
     * and simply re-applies the same statuses — never a duplicate submission.
     */
    @Transactional
    public SheetView submit(SubmitRequest request, String ipAddress) {
        Actor actor = requireMarker();
        if (request == null || request.date() == null) throw new IllegalArgumentException("Choose the attendance date.");
        School school = requireSchoolEntity(actor.schoolId);
        AcademicSession session = sessionAccess.requireCurrentSessionForTeacherWrite(actor.schoolId);
        Scope scope = resolveScope(actor, request.classId(), request.sectionId());
        LocalDate date = request.date();
        String blocked = blockedReason(school, session, date);
        if (blocked != null) throw new IllegalArgumentException(blocked);

        List<String> rosterIds = enrollments.findAttendanceRosterStudentIds(
                actor.schoolId, session.getId(), scope.classId, scope.sectionId, date);
        if (rosterIds.isEmpty()) {
            throw new IllegalArgumentException("No students are enrolled in this class for " + date + ".");
        }
        Map<String, AttendanceStatus> requested = validatedStatuses(request.students(), new HashSet<>(rosterIds));

        LocalDateTime now = LocalDateTime.now(clock);
        if (scope.sectionId != null) {
            submissions.insertSectionSubmissionIfAbsent(actor.schoolId, session.getId(), scope.classId, scope.sectionId,
                    date, actor.userId, now);
        } else {
            submissions.insertClassSubmissionIfAbsent(actor.schoolId, session.getId(), scope.classId, date, actor.userId, now);
        }
        AttendanceSession submission = submissions
                .lockSubmission(actor.schoolId, session.getId(), scope.classId, scope.sectionId, date)
                .orElseThrow(() -> new IllegalStateException("The attendance submission could not be saved. Please retry."));

        Map<String, StudentAttendance> existing = rows.findByAttendanceSessionId(submission.getId()).stream()
                .collect(Collectors.toMap(StudentAttendance::getStudentId, Function.identity(), (a, b) -> a, HashMap::new));
        List<StudentAttendance> toSave = new ArrayList<>();
        requested.forEach((studentId, status) -> {
            StudentAttendance row = existing.remove(studentId);
            if (row == null) {
                row = new StudentAttendance();
                row.setAttendanceSession(submission);
                row.setStudentId(studentId);
                row.setStatus(status);
                toSave.add(row);
            } else if (row.getStatus() != status) {
                row.setStatus(status);
                toSave.add(row);
            }
        });
        // Rows for students no longer on this day's roster (enrollment corrected since) are stale.
        if (!existing.isEmpty()) rows.deleteAll(existing.values());
        rows.saveAll(toSave);
        submission.setMarkedByUserId(actor.userId);
        submission.setUpdatedAt(now);
        submissions.save(submission);

        long absent = requested.values().stream().filter(s -> s == AttendanceStatus.ABSENT).count();
        audit.log(actor.userId, actor.role, "SAVE_ATTENDANCE", "AttendanceSession", String.valueOf(submission.getId()),
                null, "class=" + scope.classId + " section=" + scope.sectionId + " date=" + date
                        + " present=" + (requested.size() - absent) + " absent=" + absent, ipAddress);

        return sheet(actor.schoolId, scope, date, submission, rosterIds, requested, null);
    }

    /** Removes a submission (and its rows) for a day of the current session — same scope rules as marking. */
    @Transactional
    public void deleteSubmission(LocalDate date, Long classId, Long sectionId, String ipAddress) {
        Actor actor = requireMarker();
        if (date == null) throw new IllegalArgumentException("Choose the attendance date.");
        School school = requireSchoolEntity(actor.schoolId);
        AcademicSession session = sessionAccess.requireCurrentSessionForTeacherWrite(actor.schoolId);
        Scope scope = resolveScope(actor, classId, sectionId);
        if (date.isBefore(session.getStartDate()) || date.isAfter(session.getEndDate()) || date.isAfter(schoolToday(school))) {
            throw new IllegalArgumentException("Only attendance within the current academic session can be deleted.");
        }
        AttendanceSession submission = submissions.lockSubmission(actor.schoolId, session.getId(), scope.classId, scope.sectionId, date)
                .orElseThrow(() -> new NoSuchElementException("No attendance has been submitted for this day."));
        rows.deleteAll(rows.findByAttendanceSessionId(submission.getId()));
        submissions.delete(submission);
        audit.log(actor.userId, actor.role, "DELETE_ATTENDANCE", "AttendanceSession", String.valueOf(submission.getId()),
                "class=" + scope.classId + " section=" + scope.sectionId + " date=" + date, null, ipAddress);
    }

    // ─── Student figures ─────────────────────────────────────────────────

    /** type=month (month/year required) or type=year (session label required, with a monthly breakdown). */
    @Transactional(readOnly = true)
    public AttendanceSummaryDTO getStudentSummary(String studentId, String type, Integer month, Integer year, String session) {
        Long schoolId = requireSchool();
        Student student = requireStudent(schoolId, studentId);
        if ("month".equalsIgnoreCase(type)) {
            if (month == null || year == null) throw new IllegalArgumentException("month and year are required when type=month");
            LocalDate from = LocalDate.of(year, month, 1);
            return studentSummary(schoolId, student, from, from.withDayOfMonth(from.lengthOfMonth()), null);
        }
        if ("year".equalsIgnoreCase(type)) {
            if (session == null || session.isBlank()) throw new IllegalArgumentException("session is required when type=year");
            AcademicSession academicSession = academicSessions.getSessionByLabel(schoolId, session)
                    .orElseThrow(() -> new IllegalArgumentException("No academic session found for label: " + session));
            return studentSummary(schoolId, student, academicSession.getStartDate(), academicSession.getEndDate(), academicSession);
        }
        throw new IllegalArgumentException("type must be 'month' or 'year'");
    }

    /** A student's figures over an explicit range — the entry point for report cards. */
    @Transactional(readOnly = true)
    public AttendanceSummaryDTO getStudentAttendanceForDateRange(String studentId, LocalDate start, LocalDate end) {
        Long schoolId = requireSchool();
        return studentSummary(schoolId, requireStudent(schoolId, studentId), start, end, null);
    }

    @Transactional(readOnly = true)
    public DailyAttendanceDTO getDailyAttendance(String studentId, int month, int year) {
        Long schoolId = requireSchool();
        requireStudent(schoolId, studentId);
        LocalDate from = LocalDate.of(year, month, 1);
        LocalDate to = from.withDayOfMonth(from.lengthOfMonth());
        List<AttendanceRow> studentRows = rows.findStudentRows(schoolId, studentId, from, to);
        Set<String> leave = approvedLeaveKeysForStudent(schoolId, studentId, from, to);

        Map<String, String> statuses = new LinkedHashMap<>();
        List<String> absentDays = new ArrayList<>();
        List<String> approvedLeaveDays = new ArrayList<>();
        for (AttendanceRow row : studentRows) {
            String day = row.date().toString();
            statuses.put(day, row.status().name());
            if (row.status() == AttendanceStatus.ABSENT) {
                absentDays.add(day);
                if (leave.contains(leaveKey(studentId, row.date()))) approvedLeaveDays.add(day);
            }
        }
        School school = schools.findById(schoolId).orElse(null);
        List<String> nonWorkingDays = new ArrayList<>();
        if (school != null && school.getWorkingDays() != null && !school.getWorkingDays().isBlank()) {
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                if (!isWorkingWeekday(d, school.getWorkingDays())) nonWorkingDays.add(d.toString());
            }
        }
        return new DailyAttendanceDTO(new ArrayList<>(statuses.keySet()), absentDays, approvedLeaveDays, nonWorkingDays, statuses);
    }

    // ─── Class / school figures ──────────────────────────────────────────

    /**
     * Per-student figures for a class (sectionId null = every section) over a month or a session,
     * lowest percentage first. Counts only that class's (section's) submissions; currently
     * enrolled students with nothing submitted yet appear with zero days.
     */
    @Transactional(readOnly = true)
    public List<ClassAttendanceSummaryDTO> getClassSummary(String className, String type, Integer month, Integer year,
                                                           String session, Long sectionId) {
        Long schoolId = requireSchool();
        Optional<SchoolClass> cls = classes.findBySchoolIdAndName(schoolId, className);
        if (cls.isEmpty()) return List.of();
        Range range = range(schoolId, type, month, year, session);
        Map<String, Counts> counts = countsByStudent(schoolId, cls.get().getId(), sectionId, range.from, range.to);

        Set<String> studentIds = new LinkedHashSet<>(counts.keySet());
        AcademicSession current = sessionAccess.currentSessionOrNull(schoolId);
        if (current != null) {
            LocalDate today = schoolToday(requireSchoolEntity(schoolId));
            if (sectionId != null) {
                studentIds.addAll(enrollments.findActiveStudentIdsInSection(schoolId, current.getId(), cls.get().getId(), sectionId, today));
            } else {
                studentIds.addAll(enrollments.findActiveStudentIdsInClass(schoolId, current.getId(), cls.get().getId(), today));
            }
        }
        Map<String, String> names = studentNames(schoolId, studentIds);
        return studentIds.stream()
                .filter(names::containsKey)
                .map(id -> toClassRow(id, names.get(id), className, counts.getOrDefault(id, Counts.NONE)))
                .sorted(Comparator.comparingDouble(ClassAttendanceSummaryDTO::getAttendancePercentage)
                        .thenComparing(ClassAttendanceSummaryDTO::getStudentName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();
    }

    /**
     * Every student with submitted attendance in the period, school-wide, in one pass — one row
     * per student labelled with the class they were most recently marked in.
     */
    @Transactional(readOnly = true)
    public List<ClassAttendanceSummaryDTO> getSchoolSummary(String type, Integer month, Integer year, String session) {
        Long schoolId = requireSchool();
        Range range = range(schoolId, type, month, year, session);
        Map<String, Counts> counts = countsByStudent(schoolId, null, null, range.from, range.to);
        if (counts.isEmpty()) return List.of();

        Map<String, StudentLatestClass> latest = new HashMap<>();
        for (StudentLatestClass c : rows.findStudentClasses(schoolId, range.from, range.to)) {
            latest.merge(c.studentId(), c, (a, b) -> a.lastDate().isAfter(b.lastDate()) ? a : b);
        }
        Map<Long, String> classNames = classNames(schoolId);
        Map<String, String> names = studentNames(schoolId, counts.keySet());
        return counts.entrySet().stream()
                .filter(e -> names.containsKey(e.getKey()))
                .map(e -> toClassRow(e.getKey(), names.get(e.getKey()),
                        Optional.ofNullable(latest.get(e.getKey())).map(c -> classNames.get(c.classId())).orElse(null),
                        e.getValue()))
                .sorted(Comparator.comparingDouble(ClassAttendanceSummaryDTO::getAttendancePercentage))
                .toList();
    }

    /**
     * Currently enrolled students ABSENT on each of the most recent {@code minConsecutiveDays}
     * submitted days of their class/section. Walks back through the class's own submissions in
     * school-local time; a day with no row for the student ends the streak (never inferred).
     * Approved leave still counts as absent — approvedLeaveDates says which days it covered.
     */
    @Transactional(readOnly = true)
    public List<ConsecutiveAbsenceDTO> getConsecutiveAbsentees(String className, int minConsecutiveDays, Integer lookbackDays,
                                                               String session, Long sectionId) {
        if (minConsecutiveDays < 1) throw new IllegalArgumentException("minConsecutiveDays must be at least 1");
        Long schoolId = requireSchool();
        Optional<SchoolClass> cls = classes.findBySchoolIdAndName(schoolId, className);
        AcademicSession current = sessionAccess.currentSessionOrNull(schoolId);
        if (cls.isEmpty() || current == null) return List.of();

        LocalDate today = schoolToday(requireSchoolEntity(schoolId));
        int lookback = lookbackDays != null && lookbackDays > 0 ? lookbackDays : DEFAULT_ABSENCE_LOOKBACK_DAYS;
        Map<String, AbsenceStreak> streaks = currentAbsenceStreaks(schoolId, cls.get().getId(), sectionId, current, today, lookback);

        Map<String, ClassAttendanceSummaryDTO> cumulative = new HashMap<>();
        if (session != null && !session.isBlank()) {
            for (ClassAttendanceSummaryDTO row : getClassSummary(className, "year", null, null, session, sectionId)) {
                cumulative.put(row.getStudentId(), row);
            }
        }

        List<String> flagged = streaks.entrySet().stream()
                .filter(e -> e.getValue().dates().size() >= minConsecutiveDays).map(Map.Entry::getKey).toList();
        Map<String, String> names = studentNames(schoolId, flagged);
        List<ConsecutiveAbsenceDTO> result = new ArrayList<>();
        for (String studentId : flagged) {
            AbsenceStreak streak = streaks.get(studentId);
            ClassAttendanceSummaryDTO total = cumulative.get(studentId);
            result.add(new ConsecutiveAbsenceDTO(studentId, names.get(studentId), className, streak.dates().size(),
                    streak.dates().stream().map(LocalDate::toString).toList(),
                    streak.approvedLeaveDates().stream().map(LocalDate::toString).toList(),
                    total != null ? total.getTotalWorkingDays() : 0, total != null ? total.getDaysPresent() : 0,
                    total != null ? total.getDaysAbsent() : 0, total != null ? total.getAttendancePercentage() : 0.0));
        }
        result.sort(Comparator.comparingInt(ConsecutiveAbsenceDTO::getConsecutiveAbsentDays).reversed());
        return result;
    }

    /** A student's trailing run of ABSENT submitted days (oldest first) and the ones covered by approved leave. */
    public record AbsenceStreak(List<LocalDate> dates, List<LocalDate> approvedLeaveDates) {
        public static final AbsenceStreak NONE = new AbsenceStreak(List.of(), List.of());
    }

    /**
     * The current absence streak of every student currently enrolled in a class (sectionId null =
     * each of its sections, or the class itself when it has none), walking back through that
     * class/section's own submissions within the lookback window. A PRESENT day or a submitted day
     * without a row for the student ends the streak; approved leave is still absent. Bulk: a few
     * queries per section, never per student.
     */
    public Map<String, AbsenceStreak> currentAbsenceStreaks(Long schoolId, Long classId, Long sectionId,
                                                            AcademicSession current, LocalDate today, int lookbackDays) {
        List<Long> scopeSections = new ArrayList<>();
        if (sectionId != null) {
            scopeSections.add(sectionId);
        } else {
            List<Section> classSections = sections.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(schoolId, classId, true);
            if (classSections.isEmpty()) scopeSections.add(null);
            else classSections.forEach(s -> scopeSections.add(s.getId()));
        }
        Map<String, AbsenceStreak> result = new LinkedHashMap<>();
        for (Long scopeSection : scopeSections) {
            List<String> roster = enrollments.findActiveRosterStudentIds(schoolId, current.getId(), classId, scopeSection, today);
            if (roster.isEmpty()) continue;
            List<AttendanceSession> days = submissions.findForScopeBetweenDesc(schoolId, classId, scopeSection,
                    today.minusDays(lookbackDays), today);
            if (days.isEmpty()) {
                roster.forEach(id -> result.put(id, AbsenceStreak.NONE));
                continue;
            }
            Map<Long, Map<String, AttendanceStatus>> bySubmission = new HashMap<>();
            for (StudentAttendance row : rows.findByAttendanceSessionIdIn(days.stream().map(AttendanceSession::getId).toList())) {
                bySubmission.computeIfAbsent(row.getAttendanceSession().getId(), k -> new HashMap<>())
                        .put(row.getStudentId(), row.getStatus());
            }
            Set<String> leave = approvedLeaveKeys(schoolId, days.get(days.size() - 1).getAttendanceDate(), today);
            for (String studentId : roster) {
                List<LocalDate> streak = new ArrayList<>();
                for (AttendanceSession day : days) {
                    AttendanceStatus status = bySubmission.getOrDefault(day.getId(), Map.of()).get(studentId);
                    if (status != AttendanceStatus.ABSENT) break;   // PRESENT or no row: the streak ends
                    streak.add(day.getAttendanceDate());
                }
                Collections.reverse(streak);
                result.put(studentId, new AbsenceStreak(List.copyOf(streak),
                        streak.stream().filter(d -> leave.contains(leaveKey(studentId, d))).toList()));
            }
        }
        return result;
    }

    // ─── Shared helpers (also used by the dashboard, fee and notification services) ──

    /** Today in the school's own timezone. */
    public LocalDate schoolToday(School school) {
        return LocalDate.now(clock.withZone(SchoolTimeUtil.zoneId(school)));
    }

    /** "studentId|yyyy-MM-dd" keys of APPROVED leave in a school between two dates. */
    public Set<String> approvedLeaveKeys(Long schoolId, LocalDate from, LocalDate to) {
        Set<String> keys = new HashSet<>();
        for (Object[] row : leaves.findApprovedLeaveDays(schoolId, from.toString(), to.toString())) {
            keys.add(row[0] + "|" + row[1]);
        }
        return keys;
    }

    public static String leaveKey(String studentId, LocalDate date) {
        return studentId + "|" + date;
    }

    // ─── Internals ───────────────────────────────────────────────────────

    private record Actor(long schoolId, String userId, String role) {
        boolean isAdmin() { return Role.ADMIN.equals(role); }
    }

    private record Scope(long classId, String className, Long sectionId, String sectionName) {}

    private record Range(LocalDate from, LocalDate to) {}

    /** Only TEACHER and school ADMIN mark attendance; everyone else (incl. SUB_ADMIN) fails closed. */
    private Actor requireMarker() {
        Long schoolId = requireSchool();
        String role = security.getRole();
        if (!Role.TEACHER.equals(role) && !Role.ADMIN.equals(role)) {
            throw new AccessDeniedException("Only class teachers and school admins can mark attendance.");
        }
        return new Actor(schoolId, security.getUsername(), role);
    }

    private Long requireSchool() {
        Long schoolId = security.getSchoolId();
        if (schoolId == null) throw new IllegalArgumentException("No school context for the current session.");
        return schoolId;
    }

    private School requireSchoolEntity(Long schoolId) {
        return schools.findById(schoolId).orElseThrow(() -> new NoSuchElementException("School not found."));
    }

    private Student requireStudent(Long schoolId, String studentId) {
        return students.findByStudentIdAndSchoolId(studentId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Student not found: " + studentId));
    }

    /**
     * TEACHER: always their own class-teacher class/section (a different requested class or
     * section is refused, never silently widened). ADMIN: an active class of their school; a
     * class with sections needs one of its sections, a class without sections takes none.
     */
    private Scope resolveScope(Actor actor, Long classId, Long sectionId) {
        if (!actor.isAdmin()) {
            TeacherClassScopeService.TeacherScope own = classScope.resolveOwnScope(actor.userId, actor.schoolId);
            if (!own.hasClassResponsibility()) throw new AccessDeniedException("You are not assigned as a class teacher.");
            if (own.sectionRequiredButMissing()) throw new AccessDeniedException(TeacherClassScopeService.SECTION_REQUIRED_MESSAGE);
            SchoolClass cls = classes.findBySchoolIdAndName(actor.schoolId, own.className())
                    .orElseThrow(() -> new AccessDeniedException("Your assigned class was not found."));
            if (classId != null && !classId.equals(cls.getId())) {
                throw new AccessDeniedException("Teachers can only mark attendance for their assigned class.");
            }
            if (sectionId != null && !sectionId.equals(own.sectionId())) {
                throw new AccessDeniedException("Teachers can only mark attendance for their assigned section.");
            }
            return new Scope(cls.getId(), cls.getName(), own.sectionId(), sectionName(actor.schoolId, own.sectionId()));
        }
        if (classId == null) throw new IllegalArgumentException("Choose a class.");
        SchoolClass cls = classes.findByIdAndSchoolId(classId, actor.schoolId)
                .filter(SchoolClass::isActive)
                .orElseThrow(() -> new NoSuchElementException("Class not found."));
        List<Section> classSections = sections.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(actor.schoolId, classId, true);
        if (classSections.isEmpty()) {
            if (sectionId != null) throw new IllegalArgumentException("This class has no sections.");
            return new Scope(cls.getId(), cls.getName(), null, null);
        }
        if (sectionId == null) throw new IllegalArgumentException("Choose a section — attendance is marked per section.");
        Section section = classSections.stream().filter(s -> s.getId().equals(sectionId)).findFirst()
                .orElseThrow(() -> new NoSuchElementException("Section not found."));
        return new Scope(cls.getId(), cls.getName(), section.getId(), section.getName());
    }

    private String sectionName(Long schoolId, Long sectionId) {
        return sectionId == null ? null : sections.findByIdAndSchoolId(sectionId, schoolId).map(Section::getName).orElse(null);
    }

    /** Why a date cannot be marked (school-local), or null when it can. */
    private String blockedReason(School school, AcademicSession session, LocalDate date) {
        if (session == null) return "No current academic session is configured for this school.";
        if (date.isAfter(schoolToday(school))) return "Attendance cannot be marked for a future date.";
        if (date.isBefore(session.getStartDate()) || date.isAfter(session.getEndDate())) {
            return "The date is outside the current academic session.";
        }
        if (school.getWorkingDays() == null || school.getWorkingDays().isBlank()) {
            return "School working days are not configured. Please update School Settings.";
        }
        if (!isWorkingWeekday(date, school.getWorkingDays())) return date + " is not a working day.";
        if (holidays.existsBySchoolIdAndDateInRange(school.getId(), date)) return date + " is a school holiday.";
        return null;
    }

    private static boolean isWorkingWeekday(LocalDate date, String workingDays) {
        return Arrays.stream(workingDays.split(",")).map(String::trim)
                .anyMatch(day -> date.getDayOfWeek().name().equalsIgnoreCase(day));
    }

    /** Exactly one explicit status for every rostered student — no missing, unknown or duplicate students. */
    private static Map<String, AttendanceStatus> validatedStatuses(List<StudentStatus> submitted, Set<String> roster) {
        if (submitted == null || submitted.isEmpty()) throw new IllegalArgumentException("Attendance must include every student.");
        Map<String, AttendanceStatus> statuses = new LinkedHashMap<>();
        for (StudentStatus s : submitted) {
            if (s == null || s.studentId() == null || s.studentId().isBlank() || s.status() == null) {
                throw new IllegalArgumentException("Every student needs a PRESENT or ABSENT status.");
            }
            if (statuses.put(s.studentId(), s.status()) != null) {
                throw new IllegalArgumentException("Student " + s.studentId() + " appears more than once.");
            }
        }
        List<String> unknown = statuses.keySet().stream().filter(id -> !roster.contains(id)).sorted().toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Not enrolled in this class/section on this date: " + String.join(", ", unknown));
        }
        long missing = roster.stream().filter(id -> !statuses.containsKey(id)).count();
        if (missing > 0) throw new IllegalArgumentException("Attendance is missing for " + missing + " student(s). Reload the roster and try again.");
        return statuses;
    }

    private SheetView sheet(Long schoolId, Scope scope, LocalDate date, AttendanceSession submission,
                            Collection<String> rosterIds, Map<String, AttendanceStatus> saved, String blocked) {
        Set<String> leave = approvedLeaveKeys(schoolId, date, date);
        Map<String, String> names = studentNames(schoolId, rosterIds);
        List<SheetStudent> list = rosterIds.stream()
                .filter(names::containsKey)
                .map(id -> new SheetStudent(id, names.get(id), saved.get(id), leave.contains(leaveKey(id, date))))
                .sorted(Comparator.comparing(SheetStudent::name, Comparator.nullsLast(String::compareToIgnoreCase))
                        .thenComparing(SheetStudent::studentId))
                .toList();
        return new SheetView(scope.classId, scope.className, scope.sectionId, scope.sectionName, date,
                submission != null, submission != null ? submission.getMarkedByUserId() : null,
                submission != null ? submission.getMarkedAt() : null, submission != null ? submission.getUpdatedAt() : null,
                blocked == null, blocked, list);
    }

    private AttendanceSummaryDTO studentSummary(Long schoolId, Student student, LocalDate from, LocalDate to,
                                                AcademicSession breakdownSession) {
        List<AttendanceRow> studentRows = to.isBefore(from) ? List.of()
                : rows.findStudentRows(schoolId, student.getStudentId(), from, to);
        Set<String> leave = to.isBefore(from) ? Set.of() : approvedLeaveKeysForStudent(schoolId, student.getStudentId(), from, to);
        Counts counts = counts(studentRows, leave);

        AttendanceSummaryDTO dto = new AttendanceSummaryDTO();
        dto.setStudentId(student.getStudentId());
        dto.setStudentName(student.getName());
        dto.setClassName(studentRows.isEmpty() ? student.getClassName()
                : classes.findById(studentRows.get(studentRows.size() - 1).classId()).map(SchoolClass::getName)
                        .orElse(student.getClassName()));
        dto.setTotalWorkingDays(counts.workingDays());
        dto.setDaysPresent(counts.present());
        dto.setDaysAbsent(counts.absent());
        dto.setApprovedLeaveDays(counts.approvedLeave());
        dto.setAttendancePercentage(counts.percentage());
        if (breakdownSession != null) {
            Map<YearMonth, List<AttendanceRow>> byMonth = studentRows.stream()
                    .collect(Collectors.groupingBy(r -> YearMonth.from(r.date())));
            List<AttendanceSummaryDTO.MonthlyBreakdown> breakdown = new ArrayList<>();
            YearMonth first = YearMonth.from(breakdownSession.getStartDate());
            for (int i = 0; i < 12; i++) {
                YearMonth ym = first.plusMonths(i);
                Counts c = counts(byMonth.getOrDefault(ym, List.of()), leave);
                breakdown.add(new AttendanceSummaryDTO.MonthlyBreakdown(
                        ym.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH), ym.getYear(),
                        c.workingDays(), c.present(), c.absent(), c.approvedLeave(), c.percentage()));
            }
            dto.setMonthlyBreakdown(breakdown);
        }
        return dto;
    }

    private static Counts counts(List<AttendanceRow> studentRows, Set<String> leave) {
        long present = 0, absent = 0, approvedLeave = 0;
        for (AttendanceRow row : studentRows) {
            if (row.status() == AttendanceStatus.PRESENT) {
                present++;
            } else {
                absent++;
                if (leave.contains(leaveKey(row.studentId(), row.date()))) approvedLeave++;
            }
        }
        return new Counts(present, absent, approvedLeave);
    }

    /** Per-student counts for a school (classId null), class (sectionId null) or section, in two bulk queries. */
    public Map<String, Counts> countsByStudent(Long schoolId, Long classId, Long sectionId, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) return Map.of();
        Map<String, long[]> tally = new HashMap<>();
        for (AttendanceStatusCount c : rows.countByStudentAndStatus(schoolId, classId, sectionId, from, to)) {
            long[] t = tally.computeIfAbsent(c.studentId(), k -> new long[3]);
            if (c.status() == AttendanceStatus.PRESENT) t[0] += c.count(); else t[1] += c.count();
        }
        List<AttendanceRow> absentRows = rows.findAbsentRows(schoolId, classId, sectionId, from, to);
        if (!absentRows.isEmpty()) {
            Set<String> leave = approvedLeaveKeys(schoolId, from, to);
            for (AttendanceRow row : absentRows) {
                if (leave.contains(leaveKey(row.studentId(), row.date()))) {
                    tally.computeIfAbsent(row.studentId(), k -> new long[3])[2]++;
                }
            }
        }
        Map<String, Counts> result = new HashMap<>();
        tally.forEach((id, t) -> result.put(id, new Counts(t[0], t[1], t[2])));
        return result;
    }

    /** Like {@link #approvedLeaveKeys} for a single student. */
    public Set<String> approvedLeaveKeysForStudent(Long schoolId, String studentId, LocalDate from, LocalDate to) {
        Set<String> keys = new HashSet<>();
        for (Object[] row : leaves.findApprovedLeaveDaysForStudent(schoolId, studentId, from.toString(), to.toString())) {
            keys.add(row[0] + "|" + row[1]);
        }
        return keys;
    }

    private Range range(Long schoolId, String type, Integer month, Integer year, String session) {
        if ("month".equalsIgnoreCase(type)) {
            if (month == null || year == null) throw new IllegalArgumentException("month and year are required when type=month");
            LocalDate from = LocalDate.of(year, month, 1);
            return new Range(from, from.withDayOfMonth(from.lengthOfMonth()));
        }
        if ("year".equalsIgnoreCase(type)) {
            if (session == null || session.isBlank()) throw new IllegalArgumentException("session is required when type=year");
            AcademicSession s = academicSessions.getSessionByLabel(schoolId, session)
                    .orElseThrow(() -> new IllegalArgumentException("No academic session found for label: " + session));
            return new Range(s.getStartDate(), s.getEndDate());
        }
        throw new IllegalArgumentException("type must be 'month' or 'year'");
    }

    private Map<String, String> studentNames(Long schoolId, Collection<String> studentIds) {
        if (studentIds.isEmpty()) return Map.of();
        Map<String, String> names = new HashMap<>();
        for (Student s : students.findByStudentIdInAndSchoolId(new ArrayList<>(studentIds), schoolId)) {
            names.put(s.getStudentId(), s.getName() != null ? s.getName() : s.getStudentId());
        }
        return names;
    }

    private Map<Long, String> classNames(Long schoolId) {
        Map<Long, String> names = new HashMap<>();
        for (SchoolClass c : classes.findBySchoolIdOrderByDisplayOrderAsc(schoolId)) names.put(c.getId(), c.getName());
        return names;
    }

    private static ClassAttendanceSummaryDTO toClassRow(String studentId, String name, String className, Counts c) {
        return new ClassAttendanceSummaryDTO(studentId, name, className, c.workingDays(), c.present(), c.absent(),
                c.approvedLeave(), c.percentage());
    }
}
