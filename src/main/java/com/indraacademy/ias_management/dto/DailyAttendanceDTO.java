package com.indraacademy.ias_management.dto;

import java.util.List;
import java.util.Map;

public class DailyAttendanceDTO {

    /** All dates the school was open this month (yyyy-MM-dd). */
    private List<String> schoolDays;

    /** Dates the student was absent this month (yyyy-MM-dd). */
    private List<String> absentDays;
    private List<String> nonWorkingDays;
    /** Explicit per-student statuses keyed by yyyy-MM-dd. */
    private Map<String, String> statuses;

    public DailyAttendanceDTO(List<String> schoolDays, List<String> absentDays) {
        this(schoolDays, absentDays, List.of(), Map.of());
    }

    public DailyAttendanceDTO(List<String> schoolDays, List<String> absentDays, List<String> nonWorkingDays) {
        this(schoolDays, absentDays, nonWorkingDays, Map.of());
    }

    public DailyAttendanceDTO(List<String> schoolDays, List<String> absentDays,
                              List<String> nonWorkingDays, Map<String, String> statuses) {
        this.schoolDays = schoolDays;
        this.absentDays = absentDays;
        this.nonWorkingDays = nonWorkingDays;
        this.statuses = statuses;
    }

    public List<String> getSchoolDays() { return schoolDays; }
    public List<String> getAbsentDays() { return absentDays; }
    public List<String> getNonWorkingDays() { return nonWorkingDays; }
    public Map<String, String> getStatuses() { return statuses; }
}
