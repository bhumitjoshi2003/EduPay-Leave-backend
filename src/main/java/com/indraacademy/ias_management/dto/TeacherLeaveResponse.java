package com.indraacademy.ias_management.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.indraacademy.ias_management.entity.LeaveStatus;
import com.indraacademy.ias_management.entity.TeacherLeave;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

public class TeacherLeaveResponse {

    private Long id;
    private String teacherId;
    private String teacherName;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate endDate;
    private String reason;
    private LeaveStatus status;
    private LocalDateTime appliedDate;
    /** Inclusive day count (startDate..endDate) — convenience for the UI, not persisted. */
    private long days;

    public static TeacherLeaveResponse from(TeacherLeave l) {
        TeacherLeaveResponse r = new TeacherLeaveResponse();
        r.id = l.getId();
        r.teacherId = l.getTeacherId();
        r.teacherName = l.getTeacherName();
        r.startDate = l.getStartDate();
        r.endDate = l.getEndDate();
        r.reason = l.getReason();
        r.status = l.getStatus();
        r.appliedDate = l.getAppliedDate();
        r.days = ChronoUnit.DAYS.between(l.getStartDate(), l.getEndDate()) + 1;
        return r;
    }

    public Long getId() { return id; }
    public String getTeacherId() { return teacherId; }
    public String getTeacherName() { return teacherName; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public String getReason() { return reason; }
    public LeaveStatus getStatus() { return status; }
    public LocalDateTime getAppliedDate() { return appliedDate; }
    public long getDays() { return days; }
}
