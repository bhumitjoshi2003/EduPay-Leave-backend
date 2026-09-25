package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.AttendanceStatus;

/** Attendance V2 aggregate projection: how many rows a student has with one status. */
public record AttendanceStatusCount(String studentId, AttendanceStatus status, long count) {}
