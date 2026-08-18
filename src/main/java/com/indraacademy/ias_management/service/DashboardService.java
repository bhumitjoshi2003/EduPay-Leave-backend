package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.AttendanceTrendDto;
import com.indraacademy.ias_management.dto.ClassStatsDto;
import com.indraacademy.ias_management.dto.DashboardStatsDto;
import com.indraacademy.ias_management.dto.FeeTrendDto;
import com.indraacademy.ias_management.entity.Payment;
import com.indraacademy.ias_management.entity.LeaveStatus;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import com.indraacademy.ias_management.entity.Attendance;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);
    private static final DateTimeFormatter TREND_FMT = DateTimeFormatter.ofPattern("MMM yyyy");

    @Autowired private StudentRepository studentRepository;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private RefundRepository refundRepository;
    @Autowired private StudentFeesRepository studentFeesRepository;
    @Autowired private AttendanceRepository attendanceRepository;
    @Autowired private LeaveRepository leaveRepository;
    @Autowired private SchoolRepository schoolRepository;
    @Autowired private SecurityUtil securityUtil;

    // ─── /api/dashboard/stats ─────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public DashboardStatsDto getStats() {
        Long schoolId = securityUtil.getSchoolId();
        School school = schoolRepository.findById(schoolId).orElse(null);
        LocalDate today = LocalDate.now(com.indraacademy.ias_management.util.SchoolTimeUtil.zoneId(school));
        int academicStartMonth = school != null ? school.getAcademicYearStartMonth() : 4;

        long totalStudents = studentRepository.countByStatusAndSchoolId(StudentStatus.ACTIVE, schoolId);
        long totalTeachers = teacherRepository.countBySchoolId(schoolId);

        // Net fees collected this month: gross captured payments (amountPaid − platformFee)
        // minus refunds actually processed this month — each on its own period, matching how
        // real accounting reports revenue (a refund is a new event in its own period, not a
        // retroactive rewrite of the month the original payment landed in).
        long grossCollectedThisMonth = paymentRepository
                .sumAmountCollectedBySchoolIdAndMonthAndYear(schoolId, today.getMonthValue(), today.getYear());
        long refundedThisMonth = refundRepository
                .sumAmountPaiseBySchoolIdAndMonthAndYear(schoolId, today.getMonthValue(), today.getYear());
        long feesCollectedThisMonth = grossCollectedThisMonth - refundedThisMonth;

        // Overdue: distinct active students with any unpaid fee up to the current academic month
        String currentSession = currentSession(today, academicStartMonth);
        int currentAcademicMonth = calendarToAcademicMonth(today.getMonthValue(), academicStartMonth);
        long overdueStudents = studentFeesRepository
                .countDistinctOverdueStudents(schoolId, currentSession, currentAcademicMonth);

        // Today's attendance rate: (active students − absents today) / active students × 100.
        // Returns 0 if attendance has not been marked yet today (no records in attendance table for today).
        double todayAttendanceRate = 0.0;
        if (totalStudents > 0) {
            List<com.indraacademy.ias_management.entity.Attendance> todayRecords =
                    attendanceRepository.findByDateAndSchoolId(today, schoolId);
            if (!todayRecords.isEmpty()) {
                Map<String, Set<Long>> markedScopes = todayRecords.stream()
                        .filter(a -> "X".equals(a.getStudentId()) && a.getClassName() != null)
                        .collect(Collectors.groupingBy(Attendance::getClassName,
                                Collectors.mapping(Attendance::getSectionId, Collectors.toSet())));
                long markedStudents = markedScopes.entrySet().stream().mapToLong(entry -> {
                    // A legacy/global marker (null section) means the entire class was submitted.
                    if (entry.getValue().contains(null)) {
                        return studentRepository.findByClassNameAndStatusAndSchoolId(
                                entry.getKey(), StudentStatus.ACTIVE, schoolId).size();
                    }
                    return entry.getValue().stream().mapToLong(sectionId -> studentRepository
                            .findByClassNameAndSectionIdAndStatusAndSchoolId(
                                    entry.getKey(), sectionId, StudentStatus.ACTIVE, schoolId).size()).sum();
                }).sum();
                double absenceEquivalent = todayRecords.stream()
                        .filter(a -> !"X".equals(a.getStudentId()))
                        .mapToDouble(this::absenceWeight).sum();
                if (markedStudents > 0) {
                    todayAttendanceRate = Math.round((markedStudents - absenceEquivalent)
                            / markedStudents * 1000.0) / 10.0;
                }
            }
        }

        // Pending leaves: leave applications with PENDING status
        long pendingLeaves = leaveRepository.countByStatusAndSchoolId(LeaveStatus.PENDING, schoolId);

        DashboardStatsDto dto = new DashboardStatsDto();
        dto.setTotalStudents(totalStudents);
        dto.setTotalTeachers(totalTeachers);
        dto.setFeesCollectedThisMonth(feesCollectedThisMonth);
        dto.setOverdueStudents(overdueStudents);
        dto.setTodayAttendanceRate(todayAttendanceRate);
        dto.setPendingLeaves(pendingLeaves);

        log.info("Dashboard stats computed: students={}, teachers={}, fees={}, overdue={}, attendance={}%, leaves={}",
                totalStudents, totalTeachers, feesCollectedThisMonth,
                overdueStudents, todayAttendanceRate, pendingLeaves);
        return dto;
    }

    // ─── /api/dashboard/fee-trend ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<FeeTrendDto> getFeeTrend() {
        // Fetch all payments AND refunds in the last 6 calendar months — net revenue per
        // month is gross captured payments minus refunds processed that same month.
        LocalDate today = LocalDate.now();
        LocalDateTime since = today.minusMonths(5).withDayOfMonth(1).atStartOfDay();
        Long schoolId = securityUtil.getSchoolId();
        List<Payment> recent = paymentRepository.findBySchoolIdAndPaymentDateAfter(schoolId, since);
        List<com.indraacademy.ias_management.entity.Refund> recentRefunds =
                refundRepository.findBySchoolIdAndCreatedAtAfter(schoolId, since);

        // Group by "YYYY-MM" key, sum (amountPaid − platformFee) — both already paise on
        // every path that sets platformFee non-zero; see the matching fix/comment on
        // PaymentRepository.sumAmountCollectedBySchoolIdAndMonthAndYear.
        Map<String, Long> sumByMonth = new TreeMap<>(); // TreeMap keeps insertion order after we populate
        for (Payment p : recent) {
            if (p.getPaymentDate() == null) continue;
            String key = p.getPaymentDate().getYear() + "-"
                    + String.format("%02d", p.getPaymentDate().getMonthValue());
            sumByMonth.merge(key, p.getAmountPaid() - (long) p.getPlatformFee(), Long::sum);
        }
        Map<String, Long> refundedByMonth = new TreeMap<>();
        for (com.indraacademy.ias_management.entity.Refund r : recentRefunds) {
            if (r.getCreatedAt() == null) continue;
            String key = r.getCreatedAt().getYear() + "-"
                    + String.format("%02d", r.getCreatedAt().getMonthValue());
            refundedByMonth.merge(key, r.getAmountPaise(), Long::sum);
        }

        // Build ordered result covering all 6 months (fill 0 for months with no data)
        List<FeeTrendDto> result = new ArrayList<>();
        for (int i = 5; i >= 0; i--) {
            LocalDate month = today.minusMonths(i).withDayOfMonth(1);
            String key   = month.getYear() + "-" + String.format("%02d", month.getMonthValue());
            String label = month.format(TREND_FMT);   // e.g. "Nov 2025"
            long net = sumByMonth.getOrDefault(key, 0L) - refundedByMonth.getOrDefault(key, 0L);
            result.add(new FeeTrendDto(label, net));
        }
        return result;
    }

    // ─── /api/dashboard/class-stats ───────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ClassStatsDto> getClassStats() {
        LocalDate today = LocalDate.now();
        int calYear  = today.getYear();
        int calMonth = today.getMonthValue();

        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd   = today.withDayOfMonth(today.lengthOfMonth());

        Long schoolId = securityUtil.getSchoolId();
        List<String> classes = studentRepository.findDistinctActiveClassNamesBySchoolId(schoolId);

        List<ClassStatsDto> result = new ArrayList<>();
        for (String cls : classes) {
            long studentCount = studentRepository.findByClassNameAndStatusAndSchoolId(cls, StudentStatus.ACTIVE, schoolId).size();
            if (studentCount == 0) continue;

            long workingDays = attendanceRepository.countWorkingDaysForClass(cls, schoolId, calYear, calMonth);
            double attendanceRate = 0.0;
            if (workingDays > 0) {
                attendanceRate = calculateMarkedAttendanceRate(attendanceRepository
                        .findByClassNameAndSchoolIdAndDateBetween(cls, schoolId, monthStart, monthEnd), schoolId);
            }

            result.add(new ClassStatsDto(cls, studentCount, attendanceRate, workingDays));
        }

        // Sort by class name: numeric classes first (1-12), then non-numeric (LKG, UKG, etc.)
        result.sort(Comparator.comparing(dto -> {
            try {
                return String.format("%02d", Integer.parseInt(dto.getClassName()));
            } catch (NumberFormatException e) {
                return dto.getClassName();
            }
        }));

        return result;
    }

    // ─── /api/dashboard/attendance-trend ─────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AttendanceTrendDto> getAttendanceTrend(String className, String mode) {
        LocalDate today = LocalDate.now();
        Long schoolId = securityUtil.getSchoolId();
        com.indraacademy.ias_management.entity.School school = schoolRepository.findById(schoolId).orElse(null);

        // Count working days based on school's configured working days
        int workingDayCount = 6; // default Mon-Sat
        if (school != null && school.getWorkingDays() != null) {
            workingDayCount = (int) java.util.Arrays.stream(school.getWorkingDays().split(","))
                    .filter(d -> !d.isBlank()).count();
            workingDayCount = Math.max(1, Math.min(workingDayCount, 7));
        }

        long studentCount = studentRepository
                .findByClassNameAndStatusAndSchoolId(className, StudentStatus.ACTIVE, schoolId)
                .size();

        List<AttendanceTrendDto> result = new ArrayList<>();

        if ("weekly".equalsIgnoreCase(mode)) {
            // Last 8 complete weeks (Mon → Mon+workingDays-1)
            LocalDate weekStart = today.with(DayOfWeek.MONDAY).minusWeeks(7);
            DateTimeFormatter weekLabelFmt = DateTimeFormatter.ofPattern("d MMM");
            final int wdCount = workingDayCount;

            for (int i = 0; i < 8; i++) {
                LocalDate wStart = weekStart.plusWeeks(i);
                LocalDate wEnd   = wStart.plusDays(wdCount - 1);

                long workingDays = attendanceRepository.countDistinctWorkingDays(className, schoolId, wStart, wEnd);
                double rate = 0.0;
                if (workingDays > 0 && studentCount > 0) {
                    rate = calculateMarkedAttendanceRate(attendanceRepository
                            .findByClassNameAndSchoolIdAndDateBetween(className, schoolId, wStart, wEnd), schoolId);
                }

                String label = wStart.format(weekLabelFmt) + "–" + wEnd.format(weekLabelFmt);
                result.add(new AttendanceTrendDto(label, rate));
            }
        } else {
            // Monthly — last 6 calendar months
            for (int i = 5; i >= 0; i--) {
                LocalDate monthDate  = today.minusMonths(i);
                LocalDate monthStart = monthDate.withDayOfMonth(1);
                LocalDate monthEnd   = monthDate.withDayOfMonth(monthDate.lengthOfMonth());

                long workingDays = attendanceRepository.countDistinctWorkingDays(className, schoolId, monthStart, monthEnd);
                double rate = 0.0;
                if (workingDays > 0 && studentCount > 0) {
                    rate = calculateMarkedAttendanceRate(attendanceRepository
                            .findByClassNameAndSchoolIdAndDateBetween(className, schoolId, monthStart, monthEnd), schoolId);
                }

                result.add(new AttendanceTrendDto(monthDate.format(TREND_FMT), rate));
            }
        }

        log.info("Attendance trend computed: class={}, mode={}, points={}", className, mode, result.size());
        return result;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Returns the academic session for the given date, using the school's configured start month.
     * e.g. startMonth=4 (April): April 2026 → "2026-2027", Jan 2026 → "2025-2026"
     *      startMonth=7 (July):  July 2026  → "2026-2027", May 2026 → "2025-2026"
     */
    private String currentSession(LocalDate date, int startMonth) {
        int year = date.getYear();
        return date.getMonthValue() >= startMonth
                ? year + "-" + (year + 1)
                : (year - 1) + "-" + year;
    }

    /** Calendar month (1=Jan…12=Dec) → academic month (1 = startMonth). */
    private int calendarToAcademicMonth(int calendarMonth, int startMonth) {
        return ((calendarMonth - startMonth + 12) % 12) + 1;
    }

    private double absenceWeight(Attendance attendance) {
        String status = attendance.getStatus();
        if (status == null || "ABSENT".equalsIgnoreCase(status)) return 1.0;
        if ("HALF_DAY".equalsIgnoreCase(status)) return 0.5;
        return 0.0;
    }

    /** Calculates only from class/section scopes that were actually submitted (the X marker). */
    private double calculateMarkedAttendanceRate(List<Attendance> records, Long schoolId) {
        double possible = 0.0;
        double absent = 0.0;
        Map<LocalDate, List<Attendance>> byDate = records.stream()
                .filter(a -> a.getDate() != null).collect(Collectors.groupingBy(Attendance::getDate));
        for (List<Attendance> day : byDate.values()) {
            Map<String, Set<Long>> scopes = day.stream()
                    .filter(a -> "X".equals(a.getStudentId()) && a.getClassName() != null)
                    .collect(Collectors.groupingBy(Attendance::getClassName,
                            Collectors.mapping(Attendance::getSectionId, Collectors.toSet())));
            for (Map.Entry<String, Set<Long>> scope : scopes.entrySet()) {
                if (scope.getValue().contains(null)) {
                    possible += studentRepository.findByClassNameAndStatusAndSchoolId(
                            scope.getKey(), StudentStatus.ACTIVE, schoolId).size();
                } else {
                    possible += scope.getValue().stream().mapToLong(sectionId -> studentRepository
                            .findByClassNameAndSectionIdAndStatusAndSchoolId(
                                    scope.getKey(), sectionId, StudentStatus.ACTIVE, schoolId).size()).sum();
                }
            }
            absent += day.stream().filter(a -> !"X".equals(a.getStudentId()))
                    .mapToDouble(this::absenceWeight).sum();
        }
        if (possible == 0.0) return 0.0;
        return Math.round(Math.max(0.0, possible - absent) / possible * 1000.0) / 10.0;
    }
}
