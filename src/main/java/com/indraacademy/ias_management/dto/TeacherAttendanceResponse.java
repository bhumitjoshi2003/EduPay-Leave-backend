package com.indraacademy.ias_management.dto;

import com.indraacademy.ias_management.entity.TeacherAttendance;

import java.time.LocalDate;
import java.time.LocalDateTime;

public class TeacherAttendanceResponse {

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
}
