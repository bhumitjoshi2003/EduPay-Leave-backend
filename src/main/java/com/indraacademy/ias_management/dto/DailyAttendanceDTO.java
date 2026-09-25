package com.indraacademy.ias_management.dto;

import java.util.List;
import java.util.Map;

/**
 * A student's Attendance V2 calendar for one month. schoolDays are the dates with a submitted
 * attendance row for the student; statuses maps each of those dates to PRESENT or ABSENT;
 * absentDays is the ABSENT subset and approvedLeaveDays the ABSENT dates also covered by
 * APPROVED leave. nonWorkingDays are the month's dates that are not configured working weekdays.
 * A date in none of these lists simply had no attendance submitted for the student.
 */
public class DailyAttendanceDTO {
    private final List<String> schoolDays;
    private final List<String> absentDays;
    private final List<String> approvedLeaveDays;
    private final List<String> nonWorkingDays;
    private final Map<String, String> statuses;

    public DailyAttendanceDTO(List<String> schoolDays, List<String> absentDays, List<String> approvedLeaveDays,
                              List<String> nonWorkingDays, Map<String, String> statuses) {
        this.schoolDays = schoolDays;
        this.absentDays = absentDays;
        this.approvedLeaveDays = approvedLeaveDays;
        this.nonWorkingDays = nonWorkingDays;
        this.statuses = statuses;
    }

    public List<String> getSchoolDays() { return schoolDays; }
    public List<String> getAbsentDays() { return absentDays; }
    public List<String> getApprovedLeaveDays() { return approvedLeaveDays; }
    public List<String> getNonWorkingDays() { return nonWorkingDays; }
    public Map<String, String> getStatuses() { return statuses; }
}
