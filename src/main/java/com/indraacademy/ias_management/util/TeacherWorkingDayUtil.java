package com.indraacademy.ias_management.util;

import com.indraacademy.ias_management.entity.SchoolHoliday;
import com.indraacademy.ias_management.entity.TeacherLeave;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/**
 * "Is a teacher expected to work / already covered by leave on this date" — extracted from
 * TeacherAttendanceService's private helpers so the teacher-attendance-reminder scheduler can
 * reuse the exact same rules instead of re-deriving them. Callers are expected to have already
 * batch-fetched holidays/leaves for the relevant date range (see SchoolHolidayRepository
 * .findOverlapping and TeacherLeaveRepository.findApprovedOverlapping) — nothing here queries.
 */
public final class TeacherWorkingDayUtil {

    private TeacherWorkingDayUtil() {}

    /** A working day is a configured weekday that is not covered by any holiday. */
    public static boolean isWorkingDay(LocalDate date, String workingDaysPattern, List<SchoolHoliday> holidays) {
        return isWorkingDayOfWeek(date, workingDaysPattern) && !isHolidayDate(date, holidays);
    }

    /** Fails closed: a null/blank pattern means no day is a working day. */
    public static boolean isWorkingDayOfWeek(LocalDate date, String workingDays) {
        if (workingDays == null || workingDays.isBlank()) return false;
        String dayName = date.getDayOfWeek().name();
        return Arrays.stream(workingDays.split(","))
                .map(String::trim)
                .anyMatch(dayName::equalsIgnoreCase);
    }

    public static boolean isHolidayDate(LocalDate date, List<SchoolHoliday> holidays) {
        for (SchoolHoliday h : holidays) {
            if (!date.isBefore(h.getStartDate()) && !date.isAfter(h.getEndDate())) return true;
        }
        return false;
    }

    /**
     * Whether an APPROVED leave for this specific teacher covers this date. Callers must pass a
     * list already filtered to APPROVED (see findApprovedOverlapping) — PENDING and REJECTED
     * leaves must never be passed in, since this method does not itself check status.
     */
    public static boolean isCoveredByApprovedLeave(String teacherId, LocalDate date, List<TeacherLeave> approvedLeaves) {
        for (TeacherLeave leave : approvedLeaves) {
            if (!teacherId.equals(leave.getTeacherId())) continue;
            if (!date.isBefore(leave.getStartDate()) && !date.isAfter(leave.getEndDate())) return true;
        }
        return false;
    }
}
