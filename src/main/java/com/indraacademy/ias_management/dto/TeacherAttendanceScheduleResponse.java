package com.indraacademy.ias_management.dto;

import com.indraacademy.ias_management.entity.TeacherAttendanceSchedule;
import java.time.LocalDate;

public class TeacherAttendanceScheduleResponse {
    private Long id;
    private String scheduleType;
    private String workingDays;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;

    public static TeacherAttendanceScheduleResponse from(TeacherAttendanceSchedule schedule) {
        TeacherAttendanceScheduleResponse r = new TeacherAttendanceScheduleResponse();
        r.id = schedule.getId();
        r.scheduleType = schedule.getScheduleType();
        r.workingDays = schedule.getWorkingDays();
        r.effectiveFrom = schedule.getEffectiveFrom();
        r.effectiveTo = schedule.getEffectiveTo();
        return r;
    }
    public Long getId() { return id; }
    public String getScheduleType() { return scheduleType; }
    public String getWorkingDays() { return workingDays; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public LocalDate getEffectiveTo() { return effectiveTo; }
}

