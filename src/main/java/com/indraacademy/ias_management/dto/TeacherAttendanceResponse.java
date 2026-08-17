package com.indraacademy.ias_management.dto;

import com.indraacademy.ias_management.entity.TeacherAttendance;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

public class TeacherAttendanceResponse {

    /**
     * Non-null id values from real rows are always positive (JPA IDENTITY generation). A
     * synthesized absence (see {@link #absent}) — a working day with no persisted row at all —
     * is never written to the database, so it gets a negative id instead: unique enough within
     * one response payload for the frontend's Angular {@code trackBy} to work correctly, and
     * unambiguous proof it never collides with a real row. The frontend never sends this id back
     * to the server (admin-mark re-resolves by teacherId+date), so the sign carries no risk.
     */
    private Long id;
    private String teacherId;
    private String teacherName;
    private Long schoolId;
    private LocalDate date;
    private LocalDateTime checkInTime;
    private LocalDateTime checkOutTime;
    private String status;
    private Double latitude;
    private Double longitude;
    private Double distanceFromSchool;
    private String method;
    private boolean markedByAdmin;
    /** checkOutTime - checkInTime, in minutes. Null unless both timestamps are present. */
    private Long workDurationMinutes;

    public static TeacherAttendanceResponse from(TeacherAttendance ta, String teacherName) {
        TeacherAttendanceResponse r = new TeacherAttendanceResponse();
        r.id = ta.getId();
        r.teacherId = ta.getTeacherId();
        r.teacherName = teacherName;
        r.schoolId = ta.getSchoolId();
        r.date = ta.getDate();
        r.checkInTime = ta.getCheckInTime();
        r.checkOutTime = ta.getCheckOutTime();
        r.status = ta.getStatus();
        r.latitude = ta.getLatitude();
        r.longitude = ta.getLongitude();
        r.distanceFromSchool = ta.getDistanceFromSchool();
        r.method = ta.getMethod();
        r.markedByAdmin = ta.isMarkedByAdmin();
        r.workDurationMinutes = (ta.getCheckInTime() != null && ta.getCheckOutTime() != null)
                ? Duration.between(ta.getCheckInTime(), ta.getCheckOutTime()).toMinutes()
                : null;
        return r;
    }

    /**
     * A working day with no check-in, no admin override, and no leave record — resolved to
     * ABSENT by TeacherAttendanceService's calendar reconciliation rather than by any human
     * action. Never persisted: it exists only for the duration of building one API response, so
     * there is nothing here for an admin action to audit (see that service's Javadoc for why).
     */
    public static TeacherAttendanceResponse absent(long syntheticId, String teacherId, String teacherName,
                                                    Long schoolId, LocalDate date) {
        return synthesized(syntheticId, teacherId, teacherName, schoolId, date, "ABSENT");
    }

    /**
     * A working day with no attendance row, but covered by an APPROVED TeacherLeave — resolved to
     * ON_LEAVE instead of the ABSENT this would otherwise become. Also never persisted: the
     * TeacherLeave row is the durable, audited fact; this is just that fact projected onto one
     * day of an attendance summary, exactly as {@link #absent} projects "no leave, no check-in."
     */
    public static TeacherAttendanceResponse onLeave(long syntheticId, String teacherId, String teacherName,
                                                     Long schoolId, LocalDate date) {
        return synthesized(syntheticId, teacherId, teacherName, schoolId, date, "ON_LEAVE");
    }

    private static TeacherAttendanceResponse synthesized(long syntheticId, String teacherId, String teacherName,
                                                          Long schoolId, LocalDate date, String status) {
        TeacherAttendanceResponse r = new TeacherAttendanceResponse();
        r.id = syntheticId;
        r.teacherId = teacherId;
        r.teacherName = teacherName;
        r.schoolId = schoolId;
        r.date = date;
        r.status = status;
        r.method = "SYSTEM_INFERRED";
        r.markedByAdmin = false;
        return r;
    }

    public Long getId() { return id; }
    public String getTeacherId() { return teacherId; }
    public String getTeacherName() { return teacherName; }
    public Long getSchoolId() { return schoolId; }
    public LocalDate getDate() { return date; }
    public LocalDateTime getCheckInTime() { return checkInTime; }
    public LocalDateTime getCheckOutTime() { return checkOutTime; }
    public String getStatus() { return status; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public Double getDistanceFromSchool() { return distanceFromSchool; }
    public String getMethod() { return method; }
    public boolean isMarkedByAdmin() { return markedByAdmin; }
    public Long getWorkDurationMinutes() { return workDurationMinutes; }
}
