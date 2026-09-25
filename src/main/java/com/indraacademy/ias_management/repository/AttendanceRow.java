package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.AttendanceStatus;

import java.time.LocalDate;

/** Attendance V2 read projection: one student's status on one submitted day, with its class/section. */
public record AttendanceRow(Long studentAttendanceId, Long schoolId, String studentId, LocalDate date,
                            AttendanceStatus status, Long classId, Long sectionId) {}
