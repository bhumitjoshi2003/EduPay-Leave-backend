package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.ClassStatsDto;
import com.indraacademy.ias_management.dto.DashboardStatsDto;
import com.indraacademy.ias_management.dto.FeeTrendDto;
import com.indraacademy.ias_management.entity.Payment;
import com.indraacademy.ias_management.entity.LeaveStatus;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
    @Autowired private StudentFeesRepository studentFeesRepository;
    @Autowired private AttendanceRepository attendanceRepository;
    @Autowired private LeaveRepository leaveRepository;
    @Autowired private SecurityUtil securityUtil;

    // ─── /api/dashboard/stats ─────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public DashboardStatsDto getStats() {
        LocalDate today = LocalDate.now();
        Long schoolId = securityUtil.getSchoolId();

        long totalStudents = studentRepository.countByStatusAndSchoolId(StudentStatus.ACTIVE, schoolId);
        long totalTeachers = teacherRepository.countBySchoolId(schoolId);

        // Fees collected: sum of (amountPaid − platformFee) for current calendar month
        long feesCollectedThisMonth = paymentRepository
                .sumAmountCollectedBySchoolIdAndMonthAndYear(schoolId, today.getMonthValue(), today.getYear());

        // Overdue: distinct active students with any unpaid fee up to the current academic month
        String currentSession = currentSession(today);
        int currentAcademicMonth = calendarToAcademicMonth(today.getMonthValue());
        long overdueStudents = studentFeesRepository
                .countDistinctOverdueStudents(schoolId, currentSession, currentAcademicMonth);

        // Today's attendance rate: (active students − absents today) / active students × 100.
        // Returns 0 if attendance has not been marked yet today (no records in attendance table for today).
        double todayAttendanceRate = 0.0;
        if (totalStudents > 0) {
            List<com.indraacademy.ias_management.entity.Attendance> todayRecords =
                    attendanceRepository.findByDateAndSchoolId(today, schoolId);
            if (!todayRecords.isEmpty()) {
                long absentsToday = todayRecords.size();
                long presentToday = totalStudents - absentsToday;
                todayAttendanceRate = Math.round((double) presentToday / totalStudents * 1000.0) / 10.0;
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
        // Fetch all payments in the last 6 calendar months
        LocalDate today = LocalDate.now();
        LocalDateTime since = today.minusMonths(5).withDayOfMonth(1).atStartOfDay();
        List<Payment> recent = paymentRepository.findBySchoolIdAndPaymentDateAfter(securityUtil.getSchoolId(), since);

        // Group by "YYYY-MM" key, sum (amountPaid − platformFee)
        Map<String, Long> sumByMonth = new TreeMap<>(); // TreeMap keeps insertion order after we populate
        for (Payment p : recent) {
            if (p.getPaymentDate() == null) continue;
            String key = p.getPaymentDate().getYear() + "-"
                    + String.format("%02d", p.getPaymentDate().getMonthValue());
            sumByMonth.merge(key, (long) (p.getAmountPaid() - p.getPlatformFee()), Long::sum);
        }

        // Build ordered result covering all 6 months (fill 0 for months with no data)
        List<FeeTrendDto> result = new ArrayList<>();
        for (int i = 5; i >= 0; i--) {
            LocalDate month = today.minusMonths(i).withDayOfMonth(1);
            String key   = month.getYear() + "-" + String.format("%02d", month.getMonthValue());
            String label = month.format(TREND_FMT);   // e.g. "Nov 2025"
            result.add(new FeeTrendDto(label, sumByMonth.getOrDefault(key, 0L)));
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
                long totalAbsences = attendanceRepository
                        .findByClassNameAndSchoolIdAndDateBetween(cls, schoolId, monthStart, monthEnd).size();
                long totalPossible = workingDays * studentCount;
                attendanceRate = Math.round((double) (totalPossible - totalAbsences) / totalPossible * 1000.0) / 10.0;
            }

            result.add(new ClassStatsDto(cls, studentCount, attendanceRate));
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

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Returns the academic session for the given date.
     * April onwards → current-year to next-year (e.g. April 2026 → "2026-2027")
     * Jan–Mar       → prev-year to current-year (e.g. Jan 2026  → "2025-2026")
     */
    private String currentSession(LocalDate date) {
        int year = date.getYear();
        return date.getMonthValue() >= 4
                ? year + "-" + (year + 1)
                : (year - 1) + "-" + year;
    }

    /** Calendar month (1=Jan…12=Dec) → academic month (1=Apr…12=Mar). */
    private int calendarToAcademicMonth(int calendarMonth) {
        return calendarMonth >= 4 ? calendarMonth - 3 : calendarMonth + 9;
    }
}
