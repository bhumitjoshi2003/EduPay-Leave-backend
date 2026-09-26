package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.AttendanceInsightsDtos.*;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.service.AttendanceMath.Counts;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Attendance Insights, Phase 1: read-only figures over the current academic session (its start
 * up to the school's today), built from Attendance V2 rows only. Uses the same formula, leave
 * rule and consecutive-absence logic as {@link AttendanceService}; every figure comes from a few
 * bulk queries, never one query per student.
 */
@Service
public class AttendanceInsightsService {

    /** Phase 1 low-attendance threshold (%). */
    public static final double LOW_ATTENDANCE_THRESHOLD = 75.0;
    /** Current absence streak that needs attention. */
    public static final int STREAK_THRESHOLD = 3;
    static final int RECENT_DAYS = 10;

    private final AttendanceService attendance;
    private final StudentAttendanceRepository rows;
    private final StudentRepository students;
    private final SchoolRepository schools;
    private final SchoolClassRepository classes;
    private final SectionRepository sections;
    private final TimetableSessionAccessService sessionAccess;
    private final TeacherClassScopeService classScope;
    private final SecurityUtil security;

    public AttendanceInsightsService(AttendanceService attendance, StudentAttendanceRepository rows,
                                     StudentRepository students, SchoolRepository schools, SchoolClassRepository classes,
                                     SectionRepository sections, TimetableSessionAccessService sessionAccess,
                                     TeacherClassScopeService classScope, SecurityUtil security) {
        this.attendance = attendance;
        this.rows = rows;
        this.students = students;
        this.schools = schools;
        this.classes = classes;
        this.sections = sections;
        this.sessionAccess = sessionAccess;
        this.classScope = classScope;
        this.security = security;
    }

    // ─── Student ─────────────────────────────────────────────────────────

    /** One student's current-session insights. Access checks happen in the controller. */
    @Transactional(readOnly = true)
    public StudentInsights studentInsights(String studentId) {
        Long schoolId = requireSchool();
        Student student = students.findByStudentIdAndSchoolId(studentId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Student not found: " + studentId));
        Period period = currentPeriod(schoolId);
        List<AttendanceRow> studentRows = period.isEmpty() ? List.of()
                : rows.findStudentRows(schoolId, studentId, period.from, period.to);
        Set<String> leave = period.isEmpty() ? Set.of()
                : attendance.approvedLeaveKeysForStudent(schoolId, studentId, period.from, period.to);

        Counts total = counts(studentRows, leave);
        Map<YearMonth, List<AttendanceRow>> byMonth = studentRows.stream()
                .collect(Collectors.groupingBy(r -> YearMonth.from(r.date()), TreeMap::new, Collectors.toList()));
        List<MonthTrend> trend = byMonth.entrySet().stream().map(e -> {
            Counts c = counts(e.getValue(), leave);
            YearMonth ym = e.getKey();
            return new MonthTrend(ym.getYear(), ym.getMonthValue(),
                    ym.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + ym.getYear(),
                    c.workingDays(), c.present(), c.absent(), c.approvedLeave(), c.percentage());
        }).toList();

        List<RecentDay> recent = new ArrayList<>();
        int streak = 0;
        boolean streakOpen = true;
        for (int i = studentRows.size() - 1; i >= 0; i--) {   // rows are oldest first
            AttendanceRow row = studentRows.get(i);
            boolean absent = row.status() == AttendanceStatus.ABSENT;
            if (streakOpen && absent) streak++; else streakOpen = false;
            if (recent.size() < RECENT_DAYS) {
                recent.add(new RecentDay(row.date(), row.status().name(),
                        absent && leave.contains(AttendanceService.leaveKey(studentId, row.date()))));
            }
        }

        String className = studentRows.isEmpty() ? student.getClassName()
                : classes.findById(studentRows.get(studentRows.size() - 1).classId()).map(SchoolClass::getName)
                        .orElse(student.getClassName());
        return new StudentInsights(studentId, student.getName(), className, period.label, period.from, period.to,
                total.workingDays(), total.present(), total.absent(), total.approvedLeave(), total.percentage(),
                LOW_ATTENDANCE_THRESHOLD, isLow(total), streak, trend, recent);
    }

    // ─── Class / section ─────────────────────────────────────────────────

    /** A teacher's own class-teacher class/section — never a client-chosen one. */
    @Transactional(readOnly = true)
    public ClassInsights teacherClassInsights() {
        Long schoolId = requireSchool();
        TeacherClassScopeService.TeacherScope own = classScope.resolveOwnScope(security.getUsername(), schoolId);
        if (!own.hasClassResponsibility()) throw new AccessDeniedException("You are not assigned as a class teacher.");
        if (own.sectionRequiredButMissing()) throw new AccessDeniedException(TeacherClassScopeService.SECTION_REQUIRED_MESSAGE);
        SchoolClass cls = classes.findBySchoolIdAndName(schoolId, own.className())
                .orElseThrow(() -> new AccessDeniedException("Your assigned class was not found."));
        Section section = own.sectionId() == null ? null
                : sections.findByIdAndSchoolId(own.sectionId(), schoolId).orElse(null);
        return classInsights(schoolId, cls, section);
    }

    /** An admin's view of any active class in their school (sectionId null = the whole class). */
    @Transactional(readOnly = true)
    public ClassInsights adminClassInsights(Long classId, Long sectionId) {
        Long schoolId = requireSchool();
        SchoolClass cls = classes.findByIdAndSchoolId(classId, schoolId).filter(SchoolClass::isActive)
                .orElseThrow(() -> new NoSuchElementException("Class not found."));
        Section section = null;
        if (sectionId != null) {
            section = sections.findByIdAndSchoolId(sectionId, schoolId)
                    .filter(s -> s.isActive() && classId.equals(s.getClassId()))
                    .orElseThrow(() -> new NoSuchElementException("Section not found."));
        }
        return classInsights(schoolId, cls, section);
    }

    private ClassInsights classInsights(Long schoolId, SchoolClass cls, Section section) {
        Long sectionId = section != null ? section.getId() : null;
        Period period = currentPeriod(schoolId);
        if (period.isEmpty()) {
            return new ClassInsights(cls.getId(), cls.getName(), sectionId, section != null ? section.getName() : null,
                    null, null, null, LOW_ATTENDANCE_THRESHOLD, STREAK_THRESHOLD, 0, 0, 0.0, 0, 0, List.of());
        }
        // Current roster with each student's absence streak (a few queries per section).
        Map<String, AttendanceService.AbsenceStreak> streaks = attendance.currentAbsenceStreaks(schoolId, cls.getId(),
                sectionId, period.session, period.to, AttendanceService.DEFAULT_ABSENCE_LOOKBACK_DAYS);
        // Session counts for this class/section, grouped per student (two queries + leave).
        Map<String, Counts> counts = attendance.countsByStudent(schoolId, cls.getId(), sectionId, period.from, period.to);
        Map<String, Student> byId = streaks.isEmpty() ? Map.of()
                : students.findByStudentIdInAndSchoolId(new ArrayList<>(streaks.keySet()), schoolId).stream()
                        .collect(Collectors.toMap(Student::getStudentId, Function.identity(), (a, b) -> a));

        List<StudentRow> studentRows = new ArrayList<>();
        Counts classTotal = Counts.NONE;
        for (Map.Entry<String, AttendanceService.AbsenceStreak> e : streaks.entrySet()) {
            Student student = byId.get(e.getKey());
            if (student == null) continue;
            Counts c = counts.getOrDefault(e.getKey(), Counts.NONE);
            classTotal = classTotal.plus(c);
            studentRows.add(new StudentRow(e.getKey(), student.getName() != null ? student.getName() : e.getKey(),
                    student.getSectionName(), c.workingDays(), c.present(), c.absent(), c.approvedLeave(),
                    c.percentage(), isLow(c), e.getValue().dates().size(), e.getValue().approvedLeaveDates().size()));
        }
        studentRows.sort(LOW_FIRST);
        int below = (int) studentRows.stream().filter(StudentRow::lowAttendance).count();
        int streaking = (int) studentRows.stream().filter(r -> r.currentAbsenceStreak() >= STREAK_THRESHOLD).count();
        return new ClassInsights(cls.getId(), cls.getName(), sectionId, section != null ? section.getName() : null,
                period.label, period.from, period.to, LOW_ATTENDANCE_THRESHOLD, STREAK_THRESHOLD, studentRows.size(),
                classTotal.workingDays(), classTotal.percentage(), below, streaking, List.copyOf(studentRows));
    }

    /** Low attendance first (lowest %), then everyone else by %, students with nothing recorded last. */
    static final Comparator<StudentRow> LOW_FIRST = Comparator
            .comparing((StudentRow r) -> r.submittedDays() == 0)
            .thenComparing(r -> !r.lowAttendance())
            .thenComparingDouble(StudentRow::percentage)
            .thenComparing(r -> -r.currentAbsenceStreak())
            .thenComparing(StudentRow::studentName, String.CASE_INSENSITIVE_ORDER);

    // ─── Internals ───────────────────────────────────────────────────────

    private record Period(AcademicSession session, String label, LocalDate from, LocalDate to) {
        boolean isEmpty() { return session == null || to.isBefore(from); }
    }

    /** The current session from its start to the school's today (or the session end, if earlier). */
    private Period currentPeriod(Long schoolId) {
        AcademicSession session = sessionAccess.currentSessionOrNull(schoolId);
        if (session == null) return new Period(null, null, null, null);
        School school = schools.findById(schoolId).orElseThrow(() -> new NoSuchElementException("School not found."));
        LocalDate today = attendance.schoolToday(school);
        LocalDate to = today.isAfter(session.getEndDate()) ? session.getEndDate() : today;
        return new Period(session, session.getLabel(), session.getStartDate(), to);
    }

    private static Counts counts(List<AttendanceRow> studentRows, Set<String> leave) {
        long present = 0, absent = 0, approvedLeave = 0;
        for (AttendanceRow row : studentRows) {
            if (row.status() == AttendanceStatus.PRESENT) {
                present++;
            } else {
                absent++;
                if (leave.contains(AttendanceService.leaveKey(row.studentId(), row.date()))) approvedLeave++;
            }
        }
        return new Counts(present, absent, approvedLeave);
    }

    private static boolean isLow(Counts c) {
        return c.workingDays() > 0 && c.percentage() < LOW_ATTENDANCE_THRESHOLD;
    }

    private Long requireSchool() {
        Long schoolId = security.getSchoolId();
        if (schoolId == null) throw new IllegalArgumentException("No school context for the current session.");
        return schoolId;
    }
}
