package com.indraacademy.ias_management.dto;

import java.util.List;

public class DailyAttendanceDTO {

    /** All dates the school was open this month (yyyy-MM-dd). */
    private List<String> schoolDays;

    /** Dates the student was absent this month (yyyy-MM-dd). */
    private List<String> absentDays;

    public DailyAttendanceDTO(List<String> schoolDays, List<String> absentDays) {
        this.schoolDays = schoolDays;
        this.absentDays = absentDays;
    }

    public List<String> getSchoolDays() { return schoolDays; }
    public List<String> getAbsentDays() { return absentDays; }
}
